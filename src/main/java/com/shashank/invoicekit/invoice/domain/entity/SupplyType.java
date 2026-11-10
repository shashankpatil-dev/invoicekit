package com.shashank.invoicekit.invoice.domain.entity;

import java.math.BigDecimal;

/**
 * How GST applies to an invoice, worked out by the platform from the supply category and states.
 *
 * <p>Each type knows how it splits a tax code's rate into tax heads, so the tax calculator never
 * branches on the type.
 */
public enum SupplyType {

  /** Supplier and place of supply in the same state: GST splits equally into CGST and SGST. */
  INTRA_STATE {
    @Override
    public AppliedRates ratesFor(TaxRate taxRate) {
      BigDecimal half = taxRate.gstRate().divide(BigDecimal.TWO);
      return new AppliedRates(taxRate.gstRate(), half, half, BigDecimal.ZERO, taxRate.cessRate());
    }
  },

  /** Supplier and place of supply in different states: the full GST rate is charged as IGST. */
  INTER_STATE {
    @Override
    public AppliedRates ratesFor(TaxRate taxRate) {
      return new AppliedRates(
          taxRate.gstRate(),
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          taxRate.gstRate(),
          taxRate.cessRate());
    }
  },

  /** An export under LUT is zero-rated, so no GST or cess is charged. */
  EXPORT {
    @Override
    public AppliedRates ratesFor(TaxRate taxRate) {
      return AppliedRates.NONE;
    }
  };

  /** The rates, in percent, that this supply type charges for one tax code. */
  public abstract AppliedRates ratesFor(TaxRate taxRate);
}
