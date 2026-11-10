package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.MONEY;

import com.shashank.invoicekit.invoice.domain.entity.AppliedRates;
import com.shashank.invoicekit.invoice.domain.entity.LineItem;
import com.shashank.invoicekit.invoice.domain.entity.SupplyType;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import java.math.BigDecimal;
import java.util.List;

/**
 * Applies the superset's one rounding rule for every BU. Each line's taxable value and each tax
 * amount is rounded to 2 decimals half up on its own, and totals add up the rounded line values, so
 * the printed lines always add up to the printed totals.
 */
final class TaxCalculator {

  /** Taxes one line at its tax code's rate, split the way the supply type requires. */
  LineTax calculate(LineItem line, TaxRate taxRate, SupplyType supplyType) {
    AppliedRates rates = supplyType.ratesFor(taxRate);
    BigDecimal taxableValue = line.taxableValue();
    TaxAmounts amounts =
        new TaxAmounts(
            taxableValue,
            percentOf(taxableValue, rates.cgstRate()),
            percentOf(taxableValue, rates.sgstRate()),
            percentOf(taxableValue, rates.igstRate()),
            percentOf(taxableValue, rates.cessRate()));
    return new LineTax(taxRate.hsnCode(), rates, amounts);
  }

  /** Adds up the rounded line amounts. */
  TaxAmounts total(List<LineTax> lines) {
    return lines.stream().map(LineTax::amounts).reduce(TaxAmounts.NONE, TaxAmounts::plus);
  }

  /** Converts an already rounded invoice total to INR, rounded again to paise. */
  BigDecimal inInr(BigDecimal amount, BigDecimal exchangeRate) {
    return MONEY.round(amount.multiply(exchangeRate));
  }

  private static BigDecimal percentOf(BigDecimal amount, BigDecimal ratePercent) {
    return MONEY.round(amount.multiply(ratePercent).movePointLeft(2));
  }
}
