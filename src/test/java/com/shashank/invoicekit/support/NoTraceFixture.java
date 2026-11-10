package com.shashank.invoicekit.support;

import com.shashank.invoicekit.outbox.application.port.outbound.TraceHeaders;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Gives outbox writers an empty trace, for test slices that start without tracing.
 *
 * <p>Import it into a test with {@code @Import(NoTraceFixture.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoTraceFixture {

  @Bean
  TraceHeaders traceHeaders() {
    return Map::of;
  }
}
