package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceLifecycleStore;
import com.shashank.invoicekit.invoice.application.service.TimeOrderedUuid;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceStatus;
import com.shashank.invoicekit.invoice.domain.entity.RenderJob;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import com.shashank.invoicekit.outbox.application.port.outbound.TraceHeaders;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Moves invoices through rendering in PostgreSQL. Every statement names the invoice's financial
 * year, so PostgreSQL reads and writes only that year's partition. See docs/RENDER.md section 5.
 *
 * <p>Each move is one statement, and PostgreSQL runs a single statement atomically, so no separate
 * transactional class is needed: the invoice, its audit row and its outbox row change together or
 * not at all.
 */
public class PostgresInvoiceLifecycleStore implements InvoiceLifecycleStore {

  private static final String STATUS =
      """
      SELECT status FROM invoice WHERE id = :invoiceId AND financial_year = :financialYear
      """;

  /**
   * Stores the PDF's location, audits the move and queues {@code invoice.generated}, only while the
   * invoice is still {@code NUMBERED}. When another worker finished it first, the update matches no
   * row, so nothing is inserted either. The outbox row keeps the current trace.
   */
  private static final String FINISH =
      """
      WITH finished AS (
        UPDATE invoice
        SET status = 'STORED', uri = :uri, checksum = :checksum, updated_at = now()
        WHERE id = :invoiceId AND financial_year = :financialYear AND status = 'NUMBERED'
        RETURNING id
      ), audited AS (
        INSERT INTO invoice_audit (invoice_id, from_status, to_status, actor)
        SELECT id, 'NUMBERED', 'STORED', 'render' FROM finished
      )
      INSERT INTO outbox (id, invoice_id, topic, message_key, body, headers)
      SELECT :outboxId, id, 'invoice.generated', id::text, CAST(:generatedMessage AS jsonb),
             CAST(:traceHeaders AS jsonb)
      FROM finished
      """;

  /** Fails the invoice and audits why, only while it is still {@code NUMBERED}. */
  private static final String MARK_FAILED =
      """
      WITH failed AS (
        UPDATE invoice
        SET status = 'FAILED', updated_at = now()
        WHERE id = :invoiceId AND financial_year = :financialYear AND status = 'NUMBERED'
        RETURNING id
      )
      INSERT INTO invoice_audit (invoice_id, from_status, to_status, actor, reason)
      SELECT id, 'NUMBERED', 'FAILED', 'render', CAST(:reason AS text) FROM failed
      """;

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final TraceHeaders traceHeaders;

  public PostgresInvoiceLifecycleStore(
      JdbcClient jdbcClient, ObjectMapper objectMapper, TraceHeaders traceHeaders) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.traceHeaders = traceHeaders;
  }

  @Override
  public Optional<InvoiceStatus> status(UUID invoiceId, String financialYear) {
    return jdbcClient
        .sql(STATUS)
        .param("invoiceId", invoiceId)
        .param("financialYear", financialYear)
        .query(String.class)
        .optional()
        .map(InvoiceStatus::valueOf);
  }

  /**
   * The statement inserts its outbox row only when the invoice was finished, so one row means so.
   */
  @Override
  public boolean finish(RenderJob job, StoredDocument document) {
    return jdbcClient
            .sql(FINISH)
            .param("invoiceId", job.invoiceId())
            .param("financialYear", job.financialYear())
            .param("uri", document.uri())
            .param("checksum", document.checksum())
            .param("outboxId", TimeOrderedUuid.next())
            .param("generatedMessage", toJson(GeneratedMessage.of(job, document)))
            .param("traceHeaders", toJson(traceHeaders.current()))
            .update()
        == 1;
  }

  /** The statement inserts its audit row only when the invoice was failed, so one row means so. */
  @Override
  public boolean markFailed(RenderJob job, String reason) {
    return jdbcClient
            .sql(MARK_FAILED)
            .param("invoiceId", job.invoiceId())
            .param("financialYear", job.financialYear())
            .param("reason", reason)
            .update()
        == 1;
  }

  /** The message is plain records and strings, so failing to write it is a bug, not bad input. */
  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Could not write invoice JSON", error);
    }
  }

  /** The {@code invoice.generated} message from docs/LLD.md section 2. */
  private record GeneratedMessage(
      int schemaVersion,
      UUID invoiceId,
      @JsonProperty("bu") String businessUnit,
      String invoiceNumber,
      String uri,
      String checksum) {

    /** The message contract version notification consumers read. */
    private static final int SCHEMA_VERSION = 1;

    static GeneratedMessage of(RenderJob job, StoredDocument document) {
      return new GeneratedMessage(
          SCHEMA_VERSION,
          job.invoiceId(),
          job.businessUnit(),
          job.invoiceNumber(),
          document.uri(),
          document.checksum());
    }
  }
}
