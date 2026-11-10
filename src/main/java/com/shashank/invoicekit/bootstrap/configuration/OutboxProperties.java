package com.shashank.invoicekit.bootstrap.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for publishing outbox rows and cleaning up sent ones. A missing or non-positive value
 * stops the application from starting, because a zero interval, batch or timeout would spin, send
 * nothing, or delete rows straight after sending them.
 *
 * @param pollInterval wait between publishing runs when the outbox is caught up
 * @param batchSize most rows one publishing transaction claims and sends
 * @param sendTimeout longest wait for Kafka to acknowledge a batch
 * @param retention how long sent rows are kept before cleanup deletes them
 * @param cleanupInterval wait between cleanup runs
 * @param cleanupBatchSize most rows one cleanup statement deletes
 */
@ConfigurationProperties("invoicekit.outbox")
public record OutboxProperties(
    Duration pollInterval,
    int batchSize,
    Duration sendTimeout,
    Duration retention,
    Duration cleanupInterval,
    int cleanupBatchSize) {

  public OutboxProperties {
    requirePositive(pollInterval, "poll-interval");
    requirePositive(batchSize, "batch-size");
    requirePositive(sendTimeout, "send-timeout");
    requirePositive(retention, "retention");
    requirePositive(cleanupInterval, "cleanup-interval");
    requirePositive(cleanupBatchSize, "cleanup-batch-size");
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
        "invoicekit.outbox." + name + " must be more than zero, but was " + value);
  }
}
