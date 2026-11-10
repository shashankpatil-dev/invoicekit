package com.shashank.invoicekit.bootstrap.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for turning events into invoices. A missing or non-positive value stops the application
 * from starting, because a zero cache time, payload limit or retry wait would silently break
 * intake.
 *
 * @param concurrency consumer threads per pod reading {@code invoice.requested}
 * @param retryInitialInterval first wait before retrying an event that failed for a passing reason
 * @param retryMaxInterval longest wait between retries, at least the initial interval
 * @param referenceCacheTtl how long each pod keeps reference data before reading it again
 * @param maxPayloadBytes largest saved payload, so the render message stays under Kafka's limit
 */
@ConfigurationProperties("invoicekit.intake")
public record IntakeProperties(
    int concurrency,
    Duration retryInitialInterval,
    Duration retryMaxInterval,
    Duration referenceCacheTtl,
    int maxPayloadBytes) {

  public IntakeProperties {
    requirePositive(concurrency, "concurrency");
    requirePositive(retryInitialInterval, "retry-initial-interval");
    requirePositive(retryMaxInterval, "retry-max-interval");
    requirePositive(referenceCacheTtl, "reference-cache-ttl");
    requirePositive(maxPayloadBytes, "max-payload-bytes");
    if (retryMaxInterval.compareTo(retryInitialInterval) < 0) {
      throw new IllegalArgumentException(
          "invoicekit.intake.retry-max-interval must be at least retry-initial-interval, but was "
              + retryMaxInterval);
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || !value.isPositive()) {
      throw notPositive(name, value);
    }
  }

  private static void requirePositive(int value, String name) {
    if (value <= 0) {
      throw notPositive(name, value);
    }
  }

  private static IllegalArgumentException notPositive(String name, Object value) {
    return new IllegalArgumentException(
        "invoicekit.intake." + name + " must be more than zero, but was " + value);
  }
}
