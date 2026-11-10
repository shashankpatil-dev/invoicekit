package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceStore;
import com.shashank.invoicekit.invoice.application.service.TimeOrderedUuid;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import com.shashank.invoicekit.outbox.application.port.outbound.TraceHeaders;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Saves intake's rows in PostgreSQL, turning the payload and the render message into JSON here, so
 * the application never handles JSON. See docs/INTAKE.md section 7 for the transaction.
 */
public class PostgresInvoiceStore implements InvoiceStore {

  /** Inserts nothing when the event was claimed before, which is how a duplicate is caught. */
  private static final String CLAIM_EVENT =
      """
      INSERT INTO invoice_event (bu, source_event_id, invoice_id, financial_year)
      VALUES (:businessUnit, :sourceEventId, :invoiceId, :financialYear)
      ON CONFLICT (bu, source_event_id) DO NOTHING
      """;

  /**
   * Writes the invoice, its audit row and its outbox row in one round trip. It runs right after the
   * counter is locked, so the lock is held for this one statement and the commit. The outbox row
   * keeps the current trace, so publishing and rendering continue it.
   */
  private static final String SAVE_NUMBERED =
      """
      WITH saved AS (
        INSERT INTO invoice (id, bu, entity_code, source_event_id, financial_year, invoice_date,
                             invoice_number, shard, sequence_no, contract_version, payload, status)
        VALUES (:invoiceId, :businessUnit, :entityCode, :sourceEventId, :financialYear,
                :invoiceDate, :invoiceNumber, :shard, :sequenceNo, :contractVersion,
                CAST(:payload AS jsonb), 'NUMBERED')
        RETURNING id
      ), audited AS (
        INSERT INTO invoice_audit (invoice_id, to_status, actor)
        SELECT id, 'NUMBERED', 'intake' FROM saved
      )
      INSERT INTO outbox (id, invoice_id, topic, message_key, body, headers)
      SELECT :outboxId, id, 'invoice.render', id::text, CAST(:renderMessage AS jsonb),
             CAST(:traceHeaders AS jsonb)
      FROM saved
      """;

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final TraceHeaders traceHeaders;

  public PostgresInvoiceStore(
      JdbcClient jdbcClient, ObjectMapper objectMapper, TraceHeaders traceHeaders) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.traceHeaders = traceHeaders;
  }

  @Override
  public boolean claimEvent(
      String businessUnit, String sourceEventId, UUID invoiceId, String financialYear) {
    return jdbcClient
            .sql(CLAIM_EVENT)
            .param("businessUnit", businessUnit)
            .param("sourceEventId", sourceEventId)
            .param("invoiceId", invoiceId)
            .param("financialYear", financialYear)
            .update()
        == 1;
  }

  @Override
  public void saveNumbered(InvoicePayload invoice, InvoiceNumber number) {
    jdbcClient
        .sql(SAVE_NUMBERED)
        .param("invoiceId", invoice.invoiceId())
        .param("businessUnit", invoice.businessUnit())
        .param("entityCode", number.series().entityCode())
        .param("sourceEventId", invoice.sourceEventId())
        .param("financialYear", invoice.financialYear())
        .param("invoiceDate", invoice.invoiceDate())
        .param("invoiceNumber", number.value())
        .param("shard", number.series().shard().code())
        .param("sequenceNo", number.sequenceNo())
        .param("contractVersion", invoice.contractVersion())
        .param("payload", toJson(invoice))
        .param("outboxId", TimeOrderedUuid.next())
        .param("renderMessage", toJson(RenderMessage.of(invoice)))
        .param("traceHeaders", toJson(traceHeaders.current()))
        .update();
  }

  @Override
  public int payloadBytes(InvoicePayload invoice) {
    try {
      return objectMapper.writeValueAsBytes(invoice).length;
    } catch (JsonProcessingException error) {
      throw unwritable(error);
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw unwritable(error);
    }
  }

  /** The payload is plain records and strings, so failing to write it is a bug, not bad input. */
  private static IllegalStateException unwritable(JsonProcessingException error) {
    return new IllegalStateException("Could not write invoice JSON", error);
  }

  /**
   * The {@code invoice.render} message from docs/LLD.md section 2. The payload travels inline, so
   * the render worker never reads PostgreSQL before rendering.
   */
  private record RenderMessage(
      int schemaVersion,
      UUID invoiceId,
      @JsonProperty("bu") String businessUnit,
      String sourceEventId,
      String invoiceNumber,
      LocalDate invoiceDate,
      InvoicePayload payload) {

    /** The message contract version render workers read. */
    private static final int SCHEMA_VERSION = 1;

    static RenderMessage of(InvoicePayload invoice) {
      return new RenderMessage(
          SCHEMA_VERSION,
          invoice.invoiceId(),
          invoice.businessUnit(),
          invoice.sourceEventId(),
          invoice.invoiceNumber(),
          invoice.invoiceDate(),
          invoice);
    }
  }
}
