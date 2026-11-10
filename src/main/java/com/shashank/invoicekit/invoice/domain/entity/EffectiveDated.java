package com.shashank.invoicekit.invoice.domain.entity;

import java.time.LocalDate;

/**
 * Reference data that applies only between two dates, both included. Wording and rates change by
 * ending the old row and adding a new one, so an invoice always uses the row for its own date.
 */
public interface EffectiveDated {

  /** First day the row applies. */
  LocalDate effectiveFrom();

  /** Last day the row applies, or null while it is still current. */
  LocalDate effectiveTo();

  /** Whether the row applies on a date, such as an invoice date. */
  default boolean isEffectiveOn(LocalDate date) {
    return !date.isBefore(effectiveFrom())
        && (effectiveTo() == null || !date.isAfter(effectiveTo()));
  }
}
