package com.shashank.invoicekit.invoice.application.adaption;

import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;

/**
 * Turns one BU's events into invoice drafts. Each BU has exactly one adapter, in {@code
 * adaption/<bu>/}, so adding a BU means adding files there and nothing else.
 *
 * <p>An adapter works out proration, tiers and coupons, and checks its own BU's rules. It does no
 * I/O, tax or declarations: the platform does those the same way for every BU.
 */
public interface InvoiceAdapter {

  /** The BU this adapter serves, such as {@code subscriptions}. */
  String businessUnit();

  /** Turns the BU's event into final invoice lines and extras. No I/O. */
  InvoiceDraft adapt(InvoiceRequested event);
}
