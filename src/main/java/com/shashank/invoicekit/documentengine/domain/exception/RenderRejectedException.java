package com.shashank.invoicekit.documentengine.domain.exception;

/** The engine refused this invoice, so retrying the same request won't help. */
public class RenderRejectedException extends RuntimeException {

  public RenderRejectedException(String message) {
    super(message);
  }
}
