package com.shashank.invoicekit.invoice.domain.entity;

/**
 * Where an invoice is in its life, as {@code invoice.status} stores it. Intake saves every invoice
 * {@code NUMBERED}, and the render worker moves it on to {@code STORED} or {@code FAILED}.
 */
public enum InvoiceStatus {

  /** Numbered and saved, and waiting for its PDF. */
  NUMBERED,

  /** Its PDF is stored, and its {@code invoice.generated} message is in the outbox. */
  STORED,

  /** The recipient has been sent the invoice. */
  DELIVERED,

  /** The document engine refused it, so an operator must fix it and re-drive it. */
  FAILED,

  /** Cancelled, so it is never rendered or sent. */
  CANCELLED;

  /** Only a numbered invoice still needs its PDF. Every other status is past rendering. */
  public boolean awaitsRender() {
    return this == NUMBERED;
  }
}
