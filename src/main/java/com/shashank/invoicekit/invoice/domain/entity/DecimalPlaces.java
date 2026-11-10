package com.shashank.invoicekit.invoice.domain.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How many decimal places each kind of number has in the invoice contract, and how it is rounded.
 * These are contract rules, not settings: changing one changes every saved payload.
 */
public enum DecimalPlaces {

  /** Taxable values, tax amounts and totals. */
  MONEY(2),

  /** Tax rates in percent, such as {@code 18.00}. */
  RATE(2),

  /** Price of one unit before tax. */
  UNIT_PRICE(4),

  /** INR value of one unit of the invoice currency. */
  EXCHANGE_RATE(4),

  /** Seats, gigabytes or other units on a line. */
  QUANTITY(3);

  /** The contract rounds every amount half up, as in 0.005 becoming 0.01. */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private final int places;

  DecimalPlaces(int places) {
    this.places = places;
  }

  public int places() {
    return places;
  }

  /** Rounds a value to this many places. */
  public BigDecimal round(BigDecimal value) {
    return value.setScale(places, ROUNDING);
  }

  /** Rounds a value and writes it as the payload shows it, such as {@code 1215.00}. */
  public String format(BigDecimal value) {
    return round(value).toPlainString();
  }

  /** Whether a value has no more places than allowed, ignoring trailing zeros. */
  public boolean allows(BigDecimal value) {
    return value.stripTrailingZeros().scale() <= places;
  }
}
