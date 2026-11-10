package com.shashank.invoicekit.outbox.adapter.outbound.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.support.TracingFixture;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks that writers get the current trace as W3C headers, and nothing outside a trace. */
class MicrometerTraceHeadersTest {

  private final TracingFixture tracing = new TracingFixture();
  private final MicrometerTraceHeaders traceHeaders =
      new MicrometerTraceHeaders(tracing.tracer(), tracing.propagator());

  @Test
  void givesTheCurrentTraceAsATraceparent() {
    Span span = tracing.tracer().nextSpan().name("intake").start();
    try (Tracer.SpanInScope inScope = tracing.tracer().withSpan(span)) {
      Map<String, String> headers = traceHeaders.current();

      assertThat(TracingFixture.traceId(headers.get("traceparent")))
          .isEqualTo(span.context().traceId());
    } finally {
      span.end();
    }
  }

  @Test
  void givesNoHeadersOutsideATrace() {
    assertThat(traceHeaders.current()).isEmpty();
  }
}
