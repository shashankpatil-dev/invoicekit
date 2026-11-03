package com.shashank.invoicekit.numbering.domain.entity;

import java.util.Objects;

/**
 * Identifies one gap-free series of invoice numbers, which has its own counter.
 *
 * @param entityCode legal entity (GSTIN holder) issuing the invoice
 * @param businessUnit business unit that owns the invoice
 * @param financialYear financial year of the invoice date
 * @param shard shard chosen for the invoice's event
 */
public record SeriesKey(
    String entityCode, String businessUnit, FinancialYear financialYear, Shard shard) {

  public SeriesKey {
    requireText(entityCode, "entityCode");
    requireText(businessUnit, "businessUnit");
    Objects.requireNonNull(financialYear, "financialYear is required");
    Objects.requireNonNull(shard, "shard is required");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
