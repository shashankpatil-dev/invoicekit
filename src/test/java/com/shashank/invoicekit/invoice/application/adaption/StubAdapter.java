package com.shashank.invoicekit.invoice.application.adaption;

import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;

/** A pass-through adapter for any BU, for tests that only need an adapter to exist. */
public record StubAdapter(String businessUnit) implements InvoiceAdapter {

  @Override
  public InvoiceDraft adapt(InvoiceRequested event) {
    return IntakeFixtures.draftOf(event);
  }
}
