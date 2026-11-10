package com.shashank.invoicekit.bootstrap.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for turning events into invoices. A missing or non-positive value stops the application
 * from starting, because a zero cache time or payload limit would silently break intake.
 *
 * @param referenceCacheTtl how long each pod keeps reference data before reading it again
 * @param maxPayloadBytes largest saved payload, so the render message stays under Kafka's limit
 */
@ConfigurationProperties("invoicekit.intake")
public record IntakeProperties(Duration referenceCacheTtl, int maxPayloadBytes) {

  public IntakeProperties {
    if (referenceCacheTtl == null || !referenceCacheTtl.isPositive()) {
      throw new IllegalArgumentException(
          "invoicekit.intake.reference-cache-ttl must be more than zero, but was "
              + referenceCacheTtl);
    }
    if (maxPayloadBytes <= 0) {
      throw new IllegalArgumentException(
          "invoicekit.intake.max-payload-bytes must be more than zero, but was " + maxPayloadBytes);
    }
  }
}
