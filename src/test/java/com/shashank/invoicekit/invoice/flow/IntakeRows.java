package com.shashank.invoicekit.invoice.flow;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads back the rows intake and rendering write, and clears them between tests. */
class IntakeRows {

  /** Every table intake writes. Only intake flow tests use this database, so every row goes. */
  private static final String DELETE_ALL =
      "TRUNCATE invoice, invoice_event, invoice_counter, outbox, invoice_audit";

  private static final String INVOICE =
      """
      SELECT tableoid::regclass::text AS partition, id, entity_code, invoice_number, shard,
             sequence_no, status, uri, checksum, payload::text AS payload
      FROM invoice
      WHERE bu = :businessUnit AND source_event_id = :sourceEventId
      """;

  private static final String EVENT =
      """
      SELECT invoice_id, financial_year FROM invoice_event
      WHERE bu = :businessUnit AND source_event_id = :sourceEventId
      """;

  private static final String AUDIT =
      "SELECT from_status, to_status, actor FROM invoice_audit WHERE invoice_id = :invoiceId";

  private static final String OUTBOX =
      """
      SELECT id, topic, message_key, body::text AS body FROM outbox WHERE invoice_id = :invoiceId
      """;

  private static final String FULL_SERIES =
      """
      INSERT INTO invoice_counter (entity_code, bu, financial_year, shard, last_no)
      VALUES (:entityCode, :businessUnit, :financialYear, 'A', :lastNumber)
      """;

  private final JdbcClient jdbcClient;

  IntakeRows(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** The invoice a {@code subscriptions} event created. */
  SavedInvoice invoice(String sourceEventId) {
    return bySourceEvent(INVOICE, sourceEventId).query(SavedInvoice.class).single();
  }

  /** The claim a {@code subscriptions} event left. */
  ClaimedEvent event(String sourceEventId) {
    return bySourceEvent(EVENT, sourceEventId).query(ClaimedEvent.class).single();
  }

  List<AuditRow> audit(UUID invoiceId) {
    return jdbcClient.sql(AUDIT).param("invoiceId", invoiceId).query(AuditRow.class).list();
  }

  List<OutboxRow> outbox(UUID invoiceId) {
    return jdbcClient.sql(OUTBOX).param("invoiceId", invoiceId).query(OutboxRow.class).list();
  }

  /** How many rows a table holds. */
  int count(String table) {
    return countOf("SELECT count(*) FROM " + table);
  }

  /** How many different events have an invoice. */
  int invoicedEvents() {
    return countOf("SELECT count(DISTINCT (bu, source_event_id)) FROM invoice");
  }

  /** Sets the test entity's series to its last number, so the next one doesn't fit. */
  void fillSeries() {
    jdbcClient
        .sql(FULL_SERIES)
        .param("entityCode", IntakeEvents.ENTITY_CODE)
        .param("businessUnit", IntakeEvents.BUSINESS_UNIT)
        .param("financialYear", IntakeEvents.FINANCIAL_YEAR.label())
        .param("lastNumber", IntakeEvents.NUMBER_FORMAT.maxSequence())
        .update();
  }

  void deleteAll() {
    jdbcClient.sql(DELETE_ALL).update();
  }

  private JdbcClient.StatementSpec bySourceEvent(String sql, String sourceEventId) {
    return jdbcClient
        .sql(sql)
        .param("businessUnit", IntakeEvents.BUSINESS_UNIT)
        .param("sourceEventId", sourceEventId);
  }

  private int countOf(String sql) {
    return jdbcClient.sql(sql).query(Integer.class).single();
  }

  /**
   * One {@code invoice} row.
   *
   * @param partition the yearly partition holding it, such as {@code invoice_2026_27}
   * @param uri where its PDF is stored, null until it is rendered
   * @param checksum its stored PDF's checksum, null until it is rendered
   * @param payload the saved payload as JSON text
   */
  record SavedInvoice(
      String partition,
      UUID id,
      String entityCode,
      String invoiceNumber,
      String shard,
      long sequenceNo,
      String status,
      String uri,
      String checksum,
      String payload) {}

  record ClaimedEvent(UUID invoiceId, String financialYear) {}

  record AuditRow(String fromStatus, String toStatus, String actor) {}

  /**
   * One {@code outbox} row.
   *
   * @param body the render message as JSON text
   */
  record OutboxRow(UUID id, String topic, String messageKey, String body) {}
}
