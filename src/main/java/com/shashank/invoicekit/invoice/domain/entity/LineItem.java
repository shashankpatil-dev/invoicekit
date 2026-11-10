package com.shashank.invoicekit.invoice.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line on the invoice. After the adapter runs, each line holds only final values: proration,
 * tiers and coupons are already worked into the quantity, price and discount.
 *
 * @param description plan name, usage item or milestone
 * @param taxCode platform tax code, which selects the SAC code and rate
 * @param quantity seats, gigabytes or other units, up to 3 decimal places
 * @param unit unit code, such as {@code NOS} or {@code OTH}
 * @param unitPrice price of one unit before tax, up to 4 decimal places
 * @param discount amount taken off the line before tax; zero when the BU sends none
 * @param servicePeriodStart first day the line covers
 * @param servicePeriodEnd last day the line covers
 */
public record LineItem(
    String description,
    String taxCode,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal discount,
    LocalDate servicePeriodStart,
    LocalDate servicePeriodEnd) {

  public LineItem {
    discount = discount == null ? BigDecimal.ZERO : discount;
  }

  /** Quantity times unit price, before the discount and before rounding. */
  public BigDecimal grossValue() {
    return quantity.multiply(unitPrice);
  }

  /** The value tax is charged on: quantity times unit price, less discount, rounded to paise. */
  public BigDecimal taxableValue() {
    return DecimalPlaces.MONEY.round(grossValue().subtract(discount));
  }

  /** Whether the line says which days it covers. */
  public boolean hasServicePeriod() {
    return servicePeriodStart != null && servicePeriodEnd != null;
  }
}
