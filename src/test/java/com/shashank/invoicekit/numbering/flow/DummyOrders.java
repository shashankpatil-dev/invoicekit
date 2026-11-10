package com.shashank.invoicekit.numbering.flow;

import com.shashank.invoicekit.numbering.application.service.NumberingService;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places dummy orders the way the invoice module will number them, standing in until it exists.
 *
 * <p>Each order runs in one transaction: record its event, stop if the event was seen before,
 * otherwise take the next number and add the invoice already numbered. If anything fails, the
 * number rolls back with the event and the invoice, so the series keeps no gap.
 */
class DummyOrders {

  /** Records the order's event. A repeated event adds nothing and skips the counter. */
  private static final String RECORD_EVENT =
      """
      INSERT INTO invoice_event (bu, source_event_id, invoice_id, financial_year)
      VALUES (:businessUnit, :sourceEventId, :invoiceId, :financialYear)
      ON CONFLICT (bu, source_event_id) DO NOTHING
      """;

  /** Adds the invoice with its number, so shard, sequence and number arrive together. */
  private static final String ADD_INVOICE =
      """
      INSERT INTO invoice (id, bu, entity_code, source_event_id, financial_year, invoice_date,
                           shard, sequence_no, invoice_number, contract_version, payload, status)
      VALUES (:id, :businessUnit, :entityCode, :sourceEventId, :financialYear, :invoiceDate,
              :shard, :sequenceNo, :invoiceNumber, 1,
              jsonb_build_object('orderId', :sourceEventId, 'plan', 'monthly',
                                 'amount', 499.00, 'currency', 'INR'),
              'NUMBERED')
      """;

  /** Finds the invoice an event created, through the event row that points at it. */
  private static final String FIND_NUMBER =
      """
      SELECT invoice.shard, invoice.sequence_no, invoice.invoice_number
      FROM invoice_event
      JOIN invoice ON invoice.id = invoice_event.invoice_id
                  AND invoice.financial_year = invoice_event.financial_year
      WHERE invoice_event.bu = :businessUnit
        AND invoice_event.source_event_id = :sourceEventId
      """;

  private final TransactionTemplate transaction;
  private final JdbcClient jdbcClient;
  private final NumberingService numberingService;

  DummyOrders(
      TransactionTemplate transaction, JdbcClient jdbcClient, NumberingService numberingService) {
    this.transaction = transaction;
    this.jdbcClient = jdbcClient;
    this.numberingService = numberingService;
  }

  /**
   * Places the order and returns its invoice number. An order seen before keeps the number its
   * first delivery got.
   */
  String place(DummyOrder order) {
    return transaction.execute(
        status -> {
          UUID invoiceId = UUID.randomUUID();
          return recordEvent(invoiceId, order)
              ? addNumberedInvoice(invoiceId, order)
              : storedNumber(order).invoiceNumber();
        });
  }

  /** What the order's invoice row holds about its number. */
  StoredNumber storedNumber(DummyOrder order) {
    return jdbcClient
        .sql(FIND_NUMBER)
        .param("businessUnit", order.businessUnit().code())
        .param("sourceEventId", order.sourceEventId())
        .query(StoredNumber.class)
        .single();
  }

  int invoiceCount() {
    return jdbcClient
        .sql("SELECT count(*) FROM invoice WHERE entity_code = ?")
        .param(TestBusinessUnit.ENTITY_CODE)
        .query(Integer.class)
        .single();
  }

  /** Deletes the dummy invoices, their events and counters, so every series starts again at 1. */
  void deleteAll() {
    jdbcClient
        .sql(
            "DELETE FROM invoice_event WHERE invoice_id IN"
                + " (SELECT id FROM invoice WHERE entity_code = ?)")
        .param(TestBusinessUnit.ENTITY_CODE)
        .update();
    jdbcClient
        .sql("DELETE FROM invoice WHERE entity_code = ?")
        .param(TestBusinessUnit.ENTITY_CODE)
        .update();
    jdbcClient
        .sql("DELETE FROM invoice_counter WHERE entity_code = ?")
        .param(TestBusinessUnit.ENTITY_CODE)
        .update();
  }

  /** Returns false when the order's event was recorded before. */
  private boolean recordEvent(UUID invoiceId, DummyOrder order) {
    return jdbcClient
            .sql(RECORD_EVENT)
            .param("businessUnit", order.businessUnit().code())
            .param("sourceEventId", order.sourceEventId())
            .param("invoiceId", invoiceId)
            .param("financialYear", order.financialYear())
            .update()
        == 1;
  }

  private String addNumberedInvoice(UUID invoiceId, DummyOrder order) {
    InvoiceNumber number = numberingService.assign(order.numberingRequest());
    jdbcClient
        .sql(ADD_INVOICE)
        .param("id", invoiceId)
        .param("businessUnit", order.businessUnit().code())
        .param("entityCode", TestBusinessUnit.ENTITY_CODE)
        .param("sourceEventId", order.sourceEventId())
        .param("financialYear", order.financialYear())
        .param("invoiceDate", order.invoiceDate())
        .param("shard", number.series().shard().code())
        .param("sequenceNo", number.sequenceNo())
        .param("invoiceNumber", number.value())
        .update();
    return number.value();
  }

  /**
   * The number columns of one invoice row.
   *
   * @param shard shard letter, such as {@code A}
   * @param sequenceNo position in the shard's series
   * @param invoiceNumber formatted number, such as {@code SUBA260000000001}
   */
  record StoredNumber(String shard, long sequenceNo, String invoiceNumber) {}
}
