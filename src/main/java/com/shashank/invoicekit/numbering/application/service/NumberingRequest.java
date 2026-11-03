package com.shashank.invoicekit.numbering.application.service;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.NumberingRule;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Asks for the next number for one invoice.
 *
 * @param entityCode legal entity (GSTIN holder) issuing the invoice
 * @param businessUnit business unit that owns the invoice
 * @param sourceEventId event that created the invoice, which chooses its shard
 * @param invoiceDate invoice date, which chooses its financial year
 * @param rule how the business unit numbers invoices for this entity
 */
public record NumberingRequest(
    String entityCode,
    String businessUnit,
    String sourceEventId,
    LocalDate invoiceDate,
    NumberingRule rule) {

  public NumberingRequest {
    requireText(entityCode, "entityCode");
    requireText(businessUnit, "businessUnit");
    requireText(sourceEventId, "sourceEventId");
    Objects.requireNonNull(invoiceDate, "invoiceDate is required");
    Objects.requireNonNull(rule, "rule is required");
  }

  /** The series this invoice's number comes from. */
  public SeriesKey series() {
    return new SeriesKey(
        entityCode,
        businessUnit,
        FinancialYear.of(invoiceDate),
        Shard.forEvent(sourceEventId, rule.shards()));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
