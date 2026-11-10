package com.shashank.invoicekit.invoice.domain.entity;

/** What kind of supply a BU says an invoice is. It decides which tax and document rules apply. */
public enum SupplyCategory {

  /** A supply to a recipient in India, taxed with GST. */
  DOMESTIC,

  /** An export of services under a Letter of Undertaking, so no GST is charged. */
  EXPORT_LUT
}
