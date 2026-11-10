package com.shashank.invoicekit.invoice.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The rate behind one tax code for a range of dates. A BU picks the tax code, and the platform owns
 * the code and rate, because one SAC code can carry more than one rate.
 *
 * @param taxCode platform key the BU sends, such as {@code SAAS_18}
 * @param hsnCode SAC code printed on the line
 * @param gstRate total GST rate in percent
 * @param cessRate cess rate in percent, zero where no cess applies
 * @param effectiveFrom first day the rate applies
 * @param effectiveTo last day the rate applies, or null while it is current
 */
public record TaxRate(
    String taxCode,
    String hsnCode,
    BigDecimal gstRate,
    BigDecimal cessRate,
    LocalDate effectiveFrom,
    LocalDate effectiveTo)
    implements EffectiveDated {

  public TaxRate {
    cessRate = cessRate == null ? BigDecimal.ZERO : cessRate;
  }
}
