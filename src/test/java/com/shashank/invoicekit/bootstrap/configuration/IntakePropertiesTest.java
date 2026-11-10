package com.shashank.invoicekit.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Checks that intake settings can't switch off the consumers, retry waits, cache or payload limit
 * by accident.
 */
class IntakePropertiesTest {

  private static final int CONCURRENCY = 4;
  private static final Duration INITIAL_RETRY = Duration.ofMillis(200);
  private static final Duration MAX_RETRY = Duration.ofSeconds(30);
  private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
  private static final int PAYLOAD_LIMIT = 900_000;

  @Test
  void acceptsTheShippedSettings() {
    assertThatCode(
            () ->
                new IntakeProperties(
                    CONCURRENCY, INITIAL_RETRY, MAX_RETRY, FIVE_MINUTES, PAYLOAD_LIMIT))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsNoConsumerThreads() {
    assertThatThrownBy(
            () -> new IntakeProperties(0, INITIAL_RETRY, MAX_RETRY, FIVE_MINUTES, PAYLOAD_LIMIT))
        .hasMessageContaining("concurrency must be more than zero");
  }

  @Test
  void rejectsAZeroFirstRetryWait() {
    assertThatThrownBy(
            () ->
                new IntakeProperties(
                    CONCURRENCY, Duration.ZERO, MAX_RETRY, FIVE_MINUTES, PAYLOAD_LIMIT))
        .hasMessageContaining("retry-initial-interval must be more than zero");
  }

  @Test
  void rejectsAMissingLongestRetryWait() {
    assertThatThrownBy(
            () ->
                new IntakeProperties(CONCURRENCY, INITIAL_RETRY, null, FIVE_MINUTES, PAYLOAD_LIMIT))
        .hasMessageContaining("retry-max-interval must be more than zero");
  }

  @Test
  void rejectsALongestRetryWaitShorterThanTheFirst() {
    assertThatThrownBy(
            () ->
                new IntakeProperties(
                    CONCURRENCY, MAX_RETRY, INITIAL_RETRY, FIVE_MINUTES, PAYLOAD_LIMIT))
        .hasMessageContaining("retry-max-interval must be at least retry-initial-interval");
  }

  @Test
  void rejectsAZeroCacheTime() {
    assertThatThrownBy(
            () ->
                new IntakeProperties(
                    CONCURRENCY, INITIAL_RETRY, MAX_RETRY, Duration.ZERO, PAYLOAD_LIMIT))
        .hasMessageContaining("reference-cache-ttl must be more than zero");
  }

  @Test
  void rejectsAMissingCacheTime() {
    assertThatThrownBy(
            () -> new IntakeProperties(CONCURRENCY, INITIAL_RETRY, MAX_RETRY, null, PAYLOAD_LIMIT))
        .hasMessageContaining("reference-cache-ttl must be more than zero");
  }

  @Test
  void rejectsAMissingPayloadLimit() {
    assertThatThrownBy(
            () -> new IntakeProperties(CONCURRENCY, INITIAL_RETRY, MAX_RETRY, FIVE_MINUTES, 0))
        .hasMessageContaining("max-payload-bytes must be more than zero");
  }
}
