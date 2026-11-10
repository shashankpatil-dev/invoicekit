package com.shashank.invoicekit.outbox.flow;

import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes outbox rows as a writer would, and reads back what the publisher and cleanup did. */
class OutboxRows {

  private static final String INSERT =
      """
      INSERT INTO outbox (id, invoice_id, topic, message_key, body, created_at, sent_at)
      VALUES (:id, :invoiceId, :topic, :key, CAST(:body AS jsonb), :createdAt, :sentAt)
      """;

  private final JdbcClient jdbcClient;

  OutboxRows(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** The body saved for a key, in the form PostgreSQL prints JSON, so it compares as text. */
  static String body(String key) {
    return "{\"key\": \"" + key + "\"}";
  }

  /** A row written just now and waiting to be sent. */
  UUID unsent(String topic, String key) {
    return insert(topic, key, Duration.ZERO, null);
  }

  /** Overwrites a row's headers, to imitate a writer that saved something unreadable. */
  void replaceHeaders(UUID id, String headersJson) {
    jdbcClient
        .sql("UPDATE outbox SET headers = CAST(:headers AS jsonb) WHERE id = :id")
        .param("headers", headersJson)
        .param("id", id)
        .update();
  }

  /** A row written this long ago and still waiting to be sent. */
  UUID unsentFor(String topic, Duration age) {
    return insert(topic, UUID.randomUUID().toString(), age, null);
  }

  /** A row sent this long ago. */
  UUID sent(String topic, String key, Duration ago) {
    return insert(topic, key, ago, ago);
  }

  boolean isSent(UUID id) {
    return jdbcClient
        .sql("SELECT sent_at IS NOT NULL FROM outbox WHERE id = :id")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  boolean exists(UUID id) {
    return jdbcClient
        .sql("SELECT EXISTS (SELECT 1 FROM outbox WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  int unsentCount() {
    return jdbcClient
        .sql("SELECT count(*) FROM outbox WHERE sent_at IS NULL")
        .query(Integer.class)
        .single();
  }

  void deleteAll() {
    jdbcClient.sql("TRUNCATE outbox").update();
  }

  private UUID insert(String topic, String key, Duration createdAgo, Duration sentAgo) {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(INSERT)
        .param("id", id)
        .param("invoiceId", UUID.randomUUID())
        .param("topic", topic)
        .param("key", key)
        .param("body", body(key))
        .param("createdAt", ago(createdAgo), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("sentAt", sentAgo == null ? null : ago(sentAgo), Types.TIMESTAMP_WITH_TIMEZONE)
        .update();
    return id;
  }

  private static OffsetDateTime ago(Duration age) {
    return OffsetDateTime.now(ZoneOffset.UTC).minus(age);
  }
}
