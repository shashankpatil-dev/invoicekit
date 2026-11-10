package com.shashank.invoicekit.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Checks that outbox settings can't make the publisher spin or send nothing, or make cleanup delete
 * rows straight after sending them, by accident.
 */
class OutboxPropertiesTest {

  private static final Duration POLL = Duration.ofMillis(100);
  private static final int BATCH = 500;
  private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration RETENTION = Duration.ofHours(24);
  private static final Duration CLEANUP = Duration.ofMinutes(10);
  private static final int CLEANUP_BATCH = 5000;

  @Test
  void acceptsTheShippedSettings() {
    assertThatCode(
            () ->
                new OutboxProperties(POLL, BATCH, SEND_TIMEOUT, RETENTION, CLEANUP, CLEANUP_BATCH))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAZeroPollInterval() {
    assertThatThrownBy(
            () ->
                new OutboxProperties(
                    Duration.ZERO, BATCH, SEND_TIMEOUT, RETENTION, CLEANUP, CLEANUP_BATCH))
        .hasMessageContaining("poll-interval must be more than zero");
  }

  @Test
  void rejectsAnEmptyBatch() {
    assertThatThrownBy(
            () -> new OutboxProperties(POLL, 0, SEND_TIMEOUT, RETENTION, CLEANUP, CLEANUP_BATCH))
        .hasMessageContaining("batch-size must be more than zero");
  }

  @Test
  void rejectsAMissingSendTimeout() {
    assertThatThrownBy(
            () -> new OutboxProperties(POLL, BATCH, null, RETENTION, CLEANUP, CLEANUP_BATCH))
        .hasMessageContaining("send-timeout must be more than zero");
  }

  @Test
  void rejectsANegativeRetention() {
    assertThatThrownBy(
            () ->
                new OutboxProperties(
                    POLL, BATCH, SEND_TIMEOUT, Duration.ofHours(-1), CLEANUP, CLEANUP_BATCH))
        .hasMessageContaining("retention must be more than zero");
  }

  @Test
  void rejectsAZeroCleanupInterval() {
    assertThatThrownBy(
            () ->
                new OutboxProperties(
                    POLL, BATCH, SEND_TIMEOUT, RETENTION, Duration.ZERO, CLEANUP_BATCH))
        .hasMessageContaining("cleanup-interval must be more than zero");
  }

  @Test
  void rejectsAnEmptyCleanupBatch() {
    assertThatThrownBy(() -> new OutboxProperties(POLL, BATCH, SEND_TIMEOUT, RETENTION, CLEANUP, 0))
        .hasMessageContaining("cleanup-batch-size must be more than zero");
  }
}
