package com.shashank.invoicekit.bootstrap.configuration;

import com.shashank.invoicekit.numbering.adapter.outbound.postgres.CounterLockSequenceCounter;
import com.shashank.invoicekit.numbering.application.port.outbound.SequenceCounter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Connects numbering to its counters in PostgreSQL. */
@Configuration
public class NumberingConfiguration {

  @Bean
  public SequenceCounter sequenceCounter(JdbcClient jdbcClient, NumberingProperties properties) {
    return new CounterLockSequenceCounter(
        jdbcClient, properties.lockTimeout(), properties.statementTimeout());
  }
}
