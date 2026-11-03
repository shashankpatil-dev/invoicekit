package com.shashank.invoicekit.numbering.flow;

import com.shashank.invoicekit.bootstrap.configuration.NumberingConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.NumberingProperties;
import com.shashank.invoicekit.support.PostgresFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets up a numbering flow test: the real numbering beans from {@link NumberingConfiguration}, a
 * real PostgreSQL with the temporary test series, and the dummy order helpers.
 *
 * <p>Orders commit as they will in the invoice module, so tests run outside the usual test
 * transaction and must delete their rows afterwards. Every test class with this setup shares one
 * Spring context and one database.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JdbcTest(
    properties = {
      "spring.flyway.locations=classpath:db/migration,classpath:db/testseries",
      // One connection per racing thread in NumberingConcurrencyTest, so none waits for the pool.
      "spring.datasource.hikari.maximum-pool-size=32"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@EnableConfigurationProperties(NumberingProperties.class)
@Import({
  PostgresFixture.class,
  NumberingConfiguration.class,
  SimpleMeterRegistry.class,
  DummyOrders.class,
  SeriesReconciliation.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@interface NumberingFlowTest {}
