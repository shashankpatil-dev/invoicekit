package com.shashank.invoicekit.numbering.flow;

import com.shashank.invoicekit.numbering.application.service.NumberingRequest;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One dummy order, standing in for the order event the invoice module will receive.
 *
 * @param businessUnit business unit selling the order, which sets its number format and shards
 * @param sourceEventId event carrying the order, which picks its shard
 * @param invoiceDate invoice date, which picks its financial year
 */
record DummyOrder(TestBusinessUnit businessUnit, String sourceEventId, LocalDate invoiceDate) {

  /** A new order on its own event, as every real order has. */
  static DummyOrder newOrder(TestBusinessUnit businessUnit, LocalDate invoiceDate) {
    return new DummyOrder(businessUnit, "order-" + UUID.randomUUID(), invoiceDate);
  }

  /** The financial year the invoice row is filed under, such as {@code 2026-27}. */
  String financialYear() {
    return FinancialYear.of(invoiceDate).label();
  }

  /** Asks numbering for this order's invoice number. */
  NumberingRequest numberingRequest() {
    return new NumberingRequest(
        TestBusinessUnit.ENTITY_CODE,
        businessUnit.code(),
        sourceEventId,
        invoiceDate,
        businessUnit.rule());
  }
}
