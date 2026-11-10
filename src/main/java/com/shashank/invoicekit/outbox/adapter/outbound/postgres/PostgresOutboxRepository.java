package com.shashank.invoicekit.outbox.adapter.outbound.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.outbox.application.port.outbound.OutboxRepository;
import com.shashank.invoicekit.outbox.domain.entity.OutboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The outbox table in PostgreSQL. See docs/RENDER.md section 4 for how rows are claimed. */
public class PostgresOutboxRepository implements OutboxRepository {

  private static final Logger log = LoggerFactory.getLogger(PostgresOutboxRepository.class);

  /**
   * Locks the oldest unsent rows until the caller's transaction ends. {@code SKIP LOCKED} passes
   * over rows another publisher holds instead of waiting for them.
   */
  private static final String CLAIM_UNSENT =
      """
      SELECT id, topic, message_key, body::text AS body, headers::text AS headers
      FROM outbox
      WHERE sent_at IS NULL
      ORDER BY created_at
      LIMIT :batchSize
      FOR UPDATE SKIP LOCKED
      """;

  private static final String MARK_SENT = "UPDATE outbox SET sent_at = now() WHERE id = ANY(:ids)";

  /** Picks one batch of old sent rows through the partial index on sent_at, then deletes them. */
  private static final String DELETE_SENT_BEFORE =
      """
      DELETE FROM outbox
      WHERE id IN (SELECT id FROM outbox WHERE sent_at < :cutoff LIMIT :batchSize)
      """;

  private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;

  public PostgresOutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<OutboxMessage> claimUnsent(int batchSize) {
    return jdbcClient.sql(CLAIM_UNSENT).param("batchSize", batchSize).query(this::message).list();
  }

  @Override
  public void markSent(List<UUID> ids) {
    jdbcClient.sql(MARK_SENT).param("ids", ids.toArray(UUID[]::new)).update();
  }

  @Override
  public int deleteSentBefore(Instant cutoff, int batchSize) {
    return jdbcClient
        .sql(DELETE_SENT_BEFORE)
        .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
        .param("batchSize", batchSize)
        .update();
  }

  private OutboxMessage message(ResultSet row, int rowNumber) throws SQLException {
    return new OutboxMessage(
        row.getObject("id", UUID.class),
        row.getString("topic"),
        row.getString("message_key"),
        row.getString("body"),
        headers(row.getString("headers")));
  }

  /**
   * Writers save a flat object of strings, so anything else is a writer's bug. Headers only carry
   * the trace, so such a row is still sent, without its trace, instead of failing every batch.
   */
  private Map<String, String> headers(String json) {
    try {
      return objectMapper.readValue(json, HEADERS);
    } catch (JsonProcessingException error) {
      log.warn(
          "Sending an outbox row without its trace, because its headers are unreadable: {}", json);
      return Map.of();
    }
  }
}
