package com.shashank.invoicekit.invoice.application.service;

/** What intake did with an event that was not rejected. Either way, the event can be acked. */
public sealed interface IntakeOutcome {

  /** A new invoice was saved with this number. */
  record Created(String invoiceNumber) implements IntakeOutcome {}

  /** An earlier delivery already created the invoice, so nothing was saved and no number used. */
  record Duplicate() implements IntakeOutcome {}
}
