package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.support.PostgresFixture;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks how the store claims events in a real PostgreSQL. Saving is covered end to end by the
 * intake flow tests. Each test's rows are rolled back afterwards.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class PostgresInvoiceStoreTest {

  private static final String FINANCIAL_YEAR = "2026-27";

  @Autowired private JdbcClient jdbcClient;

  private PostgresInvoiceStore store;

  @BeforeEach
  void createStore() {
    store = new PostgresInvoiceStore(jdbcClient, IntakeFixtures.JSON);
  }

  @Test
  void claimsANewEvent() {
    assertThat(store.claimEvent("subscriptions", "event-1", UUID.randomUUID(), FINANCIAL_YEAR))
        .isTrue();
  }

  @Test
  void refusesASecondClaimOnTheSameEvent() {
    store.claimEvent("subscriptions", "event-1", UUID.randomUUID(), FINANCIAL_YEAR);

    assertThat(store.claimEvent("subscriptions", "event-1", UUID.randomUUID(), FINANCIAL_YEAR))
        .isFalse();
  }

  @Test
  void claimsTheSameEventIdUnderAnotherBusinessUnit() {
    store.claimEvent("subscriptions", "event-1", UUID.randomUUID(), FINANCIAL_YEAR);

    assertThat(store.claimEvent("usage", "event-1", UUID.randomUUID(), FINANCIAL_YEAR)).isTrue();
  }
}
