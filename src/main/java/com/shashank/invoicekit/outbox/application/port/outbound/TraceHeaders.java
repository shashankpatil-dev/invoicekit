package com.shashank.invoicekit.outbox.application.port.outbound;

import java.util.Map;

/**
 * The current trace as headers, such as {@code traceparent}, for writers to save with their outbox
 * rows. The publisher sends the message inside that trace, so it continues past the outbox.
 */
public interface TraceHeaders {

  /** The headers of the trace this thread is in, or an empty map when it is in none. */
  Map<String, String> current();
}
