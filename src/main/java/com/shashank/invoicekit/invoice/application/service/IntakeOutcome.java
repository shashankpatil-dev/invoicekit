package com.shashank.invoicekit.invoice.application.service;

/** What intake did with an event that was not rejected. Either way, the event can be acked. */
public sealed interface IntakeOutcome {

  /** What happened, in words for the intake log, such as "created invoice SUBA260000000001". */
  String summary();

  /** A new invoice was saved with this number. */
  record Created(String invoiceNumber) implements IntakeOutcome {

    @Override
    public String summary() {
      return "created invoice " + invoiceNumber;
    }
  }

  /** An earlier delivery already created the invoice, so nothing was saved and no number used. */
  record Duplicate() implements IntakeOutcome {

    @Override
    public String summary() {
      return "was a duplicate, so nothing was saved";
    }
  }
}
