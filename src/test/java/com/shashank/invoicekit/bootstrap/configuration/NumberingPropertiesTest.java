package com.shashank.invoicekit.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks that numbering settings can't switch off PostgreSQL's timeouts or the capacity alert. */
class NumberingPropertiesTest {

  private static final Duration TWO_SECONDS = Duration.ofSeconds(2);

  @Test
  void acceptsTheShippedSettings() {
    assertThatCode(() -> new NumberingProperties(TWO_SECONDS, Duration.ofSeconds(5), 0.8))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAZeroLockTimeout() {
    assertThatThrownBy(() -> new NumberingProperties(Duration.ZERO, TWO_SECONDS, 0.8))
        .hasMessageContaining("lock-timeout must be at least 1ms");
  }

  @Test
  void rejectsAMissingStatementTimeout() {
    assertThatThrownBy(() -> new NumberingProperties(TWO_SECONDS, null, 0.8))
        .hasMessageContaining("statement-timeout must be at least 1ms");
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, -0.1, 1.01})
  void rejectsAWarningOutsideZeroToOne(double warning) {
    assertThatThrownBy(() -> new NumberingProperties(TWO_SECONDS, TWO_SECONDS, warning))
        .hasMessageContaining("utilisation-warning must be above 0 and at most 1");
  }
}
