package com.shashank.invoicekit.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts a real PostgreSQL, the same version as deploy/compose, and points the datasource at it.
 *
 * <p>Import it into a test with {@code @Import(PostgresFixture.class)}. Tests with the same setup
 * share one cached context, so they also share one container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresFixture {

  private static final String IMAGE = "postgres:16.4-alpine";

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(IMAGE);
  }
}
