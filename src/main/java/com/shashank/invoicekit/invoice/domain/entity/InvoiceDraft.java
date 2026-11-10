package com.shashank.invoicekit.invoice.domain.entity;

import java.util.List;
import java.util.Map;

/**
 * What a BU adapter makes of an event: the final lines and extras to invoice. The rest of the
 * platform reads only this, so it never needs to know how a BU prorates or applies coupons.
 *
 * @param event the event the draft came from
 * @param lines final invoice lines, never null
 * @param attributes BU extras to save in the payload, never null
 */
public record InvoiceDraft(
    InvoiceRequested event, List<LineItem> lines, Map<String, String> attributes) {

  public InvoiceDraft {
    lines = ImmutableCopies.listOf(lines);
    attributes = ImmutableCopies.orderedMapOf(attributes);
  }
}
