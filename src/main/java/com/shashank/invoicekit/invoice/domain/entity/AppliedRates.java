package com.shashank.invoicekit.invoice.domain.entity;

import java.math.BigDecimal;

/**
 * The tax rates charged on one line, in percent, after the supply type has split GST into heads.
 *
 * @param gstRate total GST rate shown on the line, zero for exports
 * @param cgstRate central GST, half the rate on an intra-state supply
 * @param sgstRate state GST, half the rate on an intra-state supply
 * @param igstRate integrated GST, the full rate on an inter-state supply
 * @param cessRate cess, where the tax code has one
 */
public record AppliedRates(
    BigDecimal gstRate,
    BigDecimal cgstRate,
    BigDecimal sgstRate,
    BigDecimal igstRate,
    BigDecimal cessRate) {

  /** No tax at all, as on an export under LUT. */
  public static final AppliedRates NONE =
      new AppliedRates(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
}
