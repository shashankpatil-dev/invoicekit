package com.shashank.invoicekit.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Checks that intake settings can't switch off the cache or the payload limit by accident. */
class IntakePropertiesTest {

  private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

  @Test
  void acceptsTheShippedSettings() {
    assertThatCode(() -> new IntakeProperties(FIVE_MINUTES, 900_000)).doesNotThrowAnyException();
  }

  @Test
  void rejectsAZeroCacheTime() {
    assertThatThrownBy(() -> new IntakeProperties(Duration.ZERO, 900_000))
        .hasMessageContaining("reference-cache-ttl must be more than zero");
  }

  @Test
  void rejectsAMissingCacheTime() {
    assertThatThrownBy(() -> new IntakeProperties(null, 900_000))
        .hasMessageContaining("reference-cache-ttl must be more than zero");
  }

  @Test
  void rejectsAMissingPayloadLimit() {
    assertThatThrownBy(() -> new IntakeProperties(FIVE_MINUTES, 0))
        .hasMessageContaining("max-payload-bytes must be more than zero");
  }
}
