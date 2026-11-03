package com.shashank.invoicekit.numbering.domain.exception;

/** A number format template breaks a rule, so the service must not start with it. */
public class InvalidNumberFormatException extends IllegalArgumentException {

  public InvalidNumberFormatException(String message) {
    super(message);
  }
}
