package com.shashank.invoicekit.invoice.flow;

import com.shashank.invoicekit.adaption.subscriptions.SubscriptionsAdapter;
import com.shashank.invoicekit.bootstrap.configuration.IntakeConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.IntakeProperties;
import com.shashank.invoicekit.bootstrap.configuration.NumberingConfiguration;
import com.shashank.invoicekit.bootstrap.configuration.NumberingProperties;
import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceStore;
import com.shashank.invoicekit.support.PostgresFixture;
import com.shashank.invoicekit.support.SeriesReconciliation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.json.AutoConfigureJson;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets up an intake flow test: the real intake and numbering beans, the {@code subscriptions}
 * adapter, the application's JSON mapper, and a real PostgreSQL with the temporary test series.
 *
 * <p>Intake commits as it will in production, so tests run outside the usual test transaction and
 * must delete their rows afterwards. The invoice store is a Mockito spy that behaves normally until
 * a test tells it to fail. Every test class with this setup shares one Spring context and one
 * database, which no other tests use.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JdbcTest(
    properties = {
      "spring.flyway.locations=classpath:db/migration,classpath:db/testseries",
      // One connection per racing thread in IntakeConcurrencyTest, so none waits for the pool.
      "spring.datasource.hikari.maximum-pool-size=32"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@AutoConfigureJson
@EnableConfigurationProperties({NumberingProperties.class, IntakeProperties.class})
@Import({
  PostgresFixture.class,
  NumberingConfiguration.class,
  IntakeConfiguration.class,
  SubscriptionsAdapter.class,
  SimpleMeterRegistry.class,
  SeriesReconciliation.class,
  IntakeRows.class
})
@MockitoSpyBean(types = InvoiceStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@interface IntakeFlowTest {}
