package com.shashank.invoicekit.invoice.domain.exception;

/** Why an event was sent to the dead-letter topic. Retrying the same event won't help. */
public enum RejectionCode {

  /** The message is not JSON the event model can read. */
  INVALID_MESSAGE,

  /**
   * The event breaks a contract rule, uses a field its BU may not send, or lacks reference data.
   */
  VALIDATION_FAILED,

  /** No adapter serves the event's BU. */
  UNKNOWN_BU,

  /** The BU may not invoice from the event's entity. */
  ENTITY_NOT_ALLOWED,

  /** The number series has no numbers left until an operator adds shards. */
  SERIES_EXHAUSTED
}
