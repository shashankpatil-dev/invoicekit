package com.shashank.invoicekit.outbox.adapter.outbound.tracing;

import com.shashank.invoicekit.outbox.application.port.outbound.TraceHeaders;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;

/** Reads the current trace from Micrometer Tracing, in the W3C form the application propagates. */
public class MicrometerTraceHeaders implements TraceHeaders {

  private final Tracer tracer;
  private final Propagator propagator;

  public MicrometerTraceHeaders(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  @Override
  public Map<String, String> current() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return Map.of();
    }
    Map<String, String> headers = new HashMap<>();
    propagator.inject(span.context(), headers, Map::put);
    return headers;
  }
}
