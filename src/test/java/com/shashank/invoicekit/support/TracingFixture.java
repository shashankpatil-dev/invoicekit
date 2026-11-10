package com.shashank.invoicekit.support;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * A real OpenTelemetry tracer with W3C propagation, as the application uses, for tests that check
 * trace headers without starting Spring. Spans are recorded but never exported.
 */
public final class TracingFixture {

  private final io.opentelemetry.api.trace.Tracer otelTracer =
      SdkTracerProvider.builder().build().get("invoicekit-test");

  private final Tracer tracer =
      new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> {});

  private final Propagator propagator =
      new OtelPropagator(
          ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otelTracer);

  /** The trace ID inside a W3C {@code traceparent}: version, trace ID, span ID and flags. */
  public static String traceId(String traceparent) {
    return traceparent.split("-")[1];
  }

  public Tracer tracer() {
    return tracer;
  }

  public Propagator propagator() {
    return propagator;
  }

  /** Observations that add the current trace to each message sent, as Spring Boot sets them up. */
  public ObservationRegistry senderObservations() {
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));
    return registry;
  }
}
