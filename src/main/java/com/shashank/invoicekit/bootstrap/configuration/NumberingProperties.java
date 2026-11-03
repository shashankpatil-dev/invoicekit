package com.shashank.invoicekit.bootstrap.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for handing out invoice numbers. Invalid values stop the application from starting,
 * because PostgreSQL treats a zero timeout as no timeout at all.
 *
 * @param lockTimeout longest wait for a series' counter lock before the series counts as busy
 * @param statementTimeout longest any statement in a numbering transaction may run
 * @param utilisationWarning share of a series' yearly capacity, such as 0.8, that raises an alert
 */
@ConfigurationProperties("invoicekit.numbering")
public record NumberingProperties(
    Duration lockTimeout, Duration statementTimeout, double utilisationWarning) {

  private static final Duration SHORTEST_TIMEOUT = Duration.ofMillis(1);

  public NumberingProperties {
    requireTimeout(lockTimeout, "lock-timeout");
    requireTimeout(statementTimeout, "statement-timeout");
    if (utilisationWarning <= 0 || utilisationWarning > 1) {
      throw new IllegalArgumentException(
          "invoicekit.numbering.utilisation-warning must be above 0 and at most 1, but was "
              + utilisationWarning);
    }
  }

  private static void requireTimeout(Duration timeout, String name) {
    if (timeout == null || timeout.compareTo(SHORTEST_TIMEOUT) < 0) {
      throw new IllegalArgumentException(
          "invoicekit.numbering." + name + " must be at least 1ms, but was " + timeout);
    }
  }
}
