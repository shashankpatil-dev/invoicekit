package com.shashank.invoicekit.invoice.application.service;

import java.math.BigDecimal;

/**
 * Rounded money amounts for one line, or the sum of them for a whole invoice.
 *
 * @param taxableValue value tax is charged on
 * @param cgst central GST
 * @param sgst state GST
 * @param igst integrated GST
 * @param cess cess
 */
record TaxAmounts(
    BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal cess) {

  /** The starting point for adding up lines. */
  static final TaxAmounts NONE =
      new TaxAmounts(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

  /** Every tax head added together. */
  BigDecimal tax() {
    return cgst.add(sgst).add(igst).add(cess);
  }

  /** Taxable value plus tax. */
  BigDecimal total() {
    return taxableValue.add(tax());
  }

  /** These amounts and another's, head by head. */
  TaxAmounts plus(TaxAmounts other) {
    return new TaxAmounts(
        taxableValue.add(other.taxableValue),
        cgst.add(other.cgst),
        sgst.add(other.sgst),
        igst.add(other.igst),
        cess.add(other.cess));
  }
}
