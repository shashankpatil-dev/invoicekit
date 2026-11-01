package com.shashank.invoicekit.documentengine.domain.exception;

/** The engine couldn't be reached or failed on its side, so the render can be retried. */
public class RenderUnavailableException extends RuntimeException {

  public RenderUnavailableException(String message) {
    super(message);
  }

  public RenderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
