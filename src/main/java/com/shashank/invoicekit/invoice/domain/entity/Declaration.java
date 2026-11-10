package com.shashank.invoicekit.invoice.domain.entity;

import java.time.LocalDate;

/**
 * A legal or policy statement owned by the platform, as a row in {@code declaration}. BUs never
 * send declaration text: the event's values decide which ones apply.
 *
 * <p>Each condition is optional. An empty condition matches every invoice, and every filled one
 * must match.
 *
 * @param code stable key, such as {@code EXPORT_LUT}
 * @param body wording, which may use placeholders such as {@code {lutNumber}}
 * @param businessUnit limits it to one BU
 * @param supplyCategory limits it to domestic supply or exports
 * @param reverseCharge limits it to invoices with or without reverse charge
 * @param recipientRegistered limits it to registered or unregistered recipients
 * @param sortOrder print position, lowest first
 * @param effectiveFrom first invoice date it applies to
 * @param effectiveTo last invoice date it applies to, or null while current
 */
public record Declaration(
    String code,
    String body,
    String businessUnit,
    SupplyCategory supplyCategory,
    Boolean reverseCharge,
    Boolean recipientRegistered,
    int sortOrder,
    LocalDate effectiveFrom,
    LocalDate effectiveTo)
    implements EffectiveDated {

  /** Whether every filled condition matches the invoice, on the invoice's own date. */
  public boolean appliesTo(InvoiceRequested invoice) {
    return matches(businessUnit, invoice.businessUnit())
        && matches(supplyCategory, invoice.supplyCategory())
        && matches(reverseCharge, invoice.reverseCharge())
        && matches(recipientRegistered, invoice.recipient().isRegistered())
        && isEffectiveOn(invoice.invoiceDate());
  }

  private static boolean matches(Object condition, Object value) {
    return condition == null || condition.equals(value);
  }
}
