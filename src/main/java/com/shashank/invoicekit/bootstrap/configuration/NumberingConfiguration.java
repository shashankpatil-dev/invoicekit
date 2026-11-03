package com.shashank.invoicekit.bootstrap.configuration;

import com.shashank.invoicekit.numbering.adapter.outbound.postgres.CounterLockSequenceCounter;
import com.shashank.invoicekit.numbering.application.port.outbound.SequenceCounter;
import com.shashank.invoicekit.numbering.application.service.NumberingMetrics;
import com.shashank.invoicekit.numbering.application.service.NumberingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Connects numbering to its counters in PostgreSQL and to the application's metrics. */
@Configuration
public class NumberingConfiguration {

  @Bean
  public SequenceCounter sequenceCounter(JdbcClient jdbcClient, NumberingProperties properties) {
    return new CounterLockSequenceCounter(
        jdbcClient, properties.lockTimeout(), properties.statementTimeout());
  }

  @Bean
  public NumberingMetrics numberingMetrics(MeterRegistry registry, NumberingProperties properties) {
    return new NumberingMetrics(registry, properties.utilisationWarning());
  }

  @Bean
  public NumberingService numberingService(
      SequenceCounter sequenceCounter, NumberingMetrics numberingMetrics) {
    return new NumberingService(sequenceCounter, numberingMetrics);
  }
}
