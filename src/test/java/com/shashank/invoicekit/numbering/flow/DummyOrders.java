package com.shashank.invoicekit.numbering.flow;

import com.shashank.invoicekit.numbering.application.service.NumberingService;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places dummy orders the way the invoice module will number them, standing in until it exists.
 *
 * <p>Each order runs in one transaction: add the invoice without a number, stop if its event was
 * seen before, otherwise take the next number and store it on the invoice. If anything fails, the
 * number rolls back with the invoice, so the series keeps no gap.
 */
class DummyOrders {

  /** Adds the invoice without a number. A repeated event adds nothing and skips the counter. */
  private static final String ADD_INVOICE =
      """
      INSERT INTO invoice (id, bu, entity_code, source_event_id, financial_year, invoice_date,
                           contract_version, payload, status)
      VALUES (:id, :businessUnit, :entityCode, :sourceEventId, :financialYear, :invoiceDate, 1,
              jsonb_build_object('orderId', :sourceEventId, 'plan', 'monthly',
                                 'amount', 499.00, 'currency', 'INR'),
              'NUMBERED')
      ON CONFLICT (bu, source_event_id) DO NOTHING
      """;

  /** Stores the number on the invoice. Shard, sequence and number are always set together. */
  private static final String STORE_NUMBER =
      """
      UPDATE invoice
      SET shard = :shard, sequence_no = :sequenceNo, invoice_number = :invoiceNumber,
          updated_at = now()
      WHERE id = :id
      """;

  private static final String FIND_NUMBER =
      """
      SELECT shard, sequence_no, invoice_number FROM invoice
      WHERE bu = :businessUnit AND source_event_id = :sourceEventId
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
          return addInvoice(invoiceId, order)
              ? numberInvoice(invoiceId, order)
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

  /** Deletes the dummy invoices and their counters, so every series starts again at 1. */
  void deleteAll() {
    jdbcClient
        .sql("DELETE FROM invoice WHERE entity_code = ?")
        .param(TestBusinessUnit.ENTITY_CODE)
        .update();
    jdbcClient
        .sql("DELETE FROM invoice_counter WHERE entity_code = ?")
        .param(TestBusinessUnit.ENTITY_CODE)
        .update();
  }

  /** Returns false when the order's event already has an invoice. */
  private boolean addInvoice(UUID invoiceId, DummyOrder order) {
    return jdbcClient
            .sql(ADD_INVOICE)
            .param("id", invoiceId)
            .param("businessUnit", order.businessUnit().code())
            .param("entityCode", TestBusinessUnit.ENTITY_CODE)
            .param("sourceEventId", order.sourceEventId())
            .param("financialYear", order.financialYear())
            .param("invoiceDate", order.invoiceDate())
            .update()
        == 1;
  }

  private String numberInvoice(UUID invoiceId, DummyOrder order) {
    InvoiceNumber number = numberingService.assign(order.numberingRequest());
    jdbcClient
        .sql(STORE_NUMBER)
        .param("id", invoiceId)
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
