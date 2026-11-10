package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceStore;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;

/**
 * Refuses payloads too large for the {@code invoice.render} message, which carries the payload
 * inline and must stay under Kafka's message limit. It runs before numbering, so an oversized
 * invoice never uses a number.
 */
public final class PayloadSizeLimit {

  private final InvoiceStore store;
  private final int maxBytes;

  public PayloadSizeLimit(InvoiceStore store, int maxBytes) {
    this.store = store;
    this.maxBytes = maxBytes;
  }

  /** Rejects the event when its payload is larger than the limit. */
  public void check(InvoicePayload payload) {
    int bytes = store.payloadBytes(payload);
    if (bytes > maxBytes) {
      throw new RejectedEventException(
          RejectionCode.VALIDATION_FAILED,
          List.of("payload is " + bytes + " bytes, over the limit of " + maxBytes));
    }
  }
}
