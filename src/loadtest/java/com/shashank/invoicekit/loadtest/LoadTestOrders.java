package com.shashank.invoicekit.loadtest;

import com.shashank.invoicekit.numbering.application.service.NumberingRequest;
import com.shashank.invoicekit.numbering.application.service.NumberingService;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places dummy orders the same way the numbering flow tests do, standing in for the invoice module.
 *
 * <p>Each order runs in one transaction: add the invoice without a number, stop if its event was
 * seen before, otherwise take the next number and store it on the invoice. If anything fails, the
 * number rolls back with the invoice, so the series keeps no gap.
 */
@Component
@Profile("loadtest")
class LoadTestOrders {

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
      SELECT invoice_number FROM invoice
      WHERE bu = :businessUnit AND source_event_id = :sourceEventId
      """;

  private final TransactionTemplate transaction;
  private final JdbcClient jdbcClient;
  private final NumberingService numberingService;
  private final LoadTestBusinessUnits businessUnits;
  private final String entityCode;

  LoadTestOrders(
      TransactionTemplate transaction,
      JdbcClient jdbcClient,
      NumberingService numberingService,
      LoadTestBusinessUnits businessUnits,
      LoadTestProperties properties) {
    this.transaction = transaction;
    this.jdbcClient = jdbcClient;
    this.numberingService = numberingService;
    this.businessUnits = businessUnits;
    this.entityCode = properties.entityCode();
  }

  /**
   * Places the order and returns its invoice number. An order seen before keeps the number its
   * first delivery got.
   */
  String place(LoadTestOrder order) {
    NumberingRequest request =
        new NumberingRequest(
            entityCode,
            order.businessUnit(),
            order.sourceEventId(),
            order.invoiceDate(),
            businessUnits.ruleFor(order.businessUnit()));
    return transaction.execute(
        status -> {
          UUID invoiceId = UUID.randomUUID();
          return addInvoice(invoiceId, order)
              ? numberInvoice(invoiceId, request)
              : storedNumber(order);
        });
  }

  /** Deletes the dummy invoices and their counters, so every series starts again at 1. */
  void deleteAll() {
    transaction.executeWithoutResult(
        status -> {
          jdbcClient.sql("DELETE FROM invoice WHERE entity_code = ?").param(entityCode).update();
          jdbcClient
              .sql("DELETE FROM invoice_counter WHERE entity_code = ?")
              .param(entityCode)
              .update();
        });
  }

  /** Returns false when the order's event already has an invoice. */
  private boolean addInvoice(UUID invoiceId, LoadTestOrder order) {
    return jdbcClient
            .sql(ADD_INVOICE)
            .param("id", invoiceId)
            .param("businessUnit", order.businessUnit())
            .param("entityCode", entityCode)
            .param("sourceEventId", order.sourceEventId())
            .param("financialYear", order.financialYear())
            .param("invoiceDate", order.invoiceDate())
            .update()
        == 1;
  }

  private String numberInvoice(UUID invoiceId, NumberingRequest request) {
    InvoiceNumber number = numberingService.assign(request);
    jdbcClient
        .sql(STORE_NUMBER)
        .param("id", invoiceId)
        .param("shard", number.series().shard().code())
        .param("sequenceNo", number.sequenceNo())
        .param("invoiceNumber", number.value())
        .update();
    return number.value();
  }

  private String storedNumber(LoadTestOrder order) {
    return jdbcClient
        .sql(FIND_NUMBER)
        .param("businessUnit", order.businessUnit())
        .param("sourceEventId", order.sourceEventId())
        .query(String.class)
        .single();
  }
}
