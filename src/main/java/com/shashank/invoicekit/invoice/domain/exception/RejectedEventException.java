package com.shashank.invoicekit.invoice.domain.exception;

import java.util.List;

/**
 * An event that can never become an invoice. It carries every reason found, so the dead-letter
 * message tells the BU everything to fix at once.
 */
public class RejectedEventException extends RuntimeException {

  private final RejectionCode code;
  private final List<String> reasons;

  public RejectedEventException(RejectionCode code, List<String> reasons) {
    super(code + ": " + String.join("; ", reasons));
    this.code = code;
    this.reasons = List.copyOf(reasons);
  }

  public RejectionCode code() {
    return code;
  }

  public List<String> reasons() {
    return reasons;
  }
}
