package com.shashank.invoicekit.loadtest;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * One dummy order sent by the load test.
 *
 * @param businessUnit business unit selling the order, which sets its number format and shards
 * @param sourceEventId event carrying the order, which picks its shard
 * @param invoiceDate invoice date, which picks its financial year
 */
record LoadTestOrder(
    @NotBlank String businessUnit, @NotBlank String sourceEventId, @NotNull LocalDate invoiceDate) {

  /** The financial year the invoice row is filed under, such as {@code 2026-27}. */
  String financialYear() {
    return FinancialYear.of(invoiceDate).label();
  }
}
