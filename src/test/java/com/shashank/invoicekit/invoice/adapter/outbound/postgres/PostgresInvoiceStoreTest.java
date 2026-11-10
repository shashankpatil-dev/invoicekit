package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_PAYLOAD;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.JSON;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.tree;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import com.shashank.invoicekit.support.PostgresFixture;
import java.util.Map;
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
 * Checks how the store claims events, and which trace it saves with the render message, in a real
 * PostgreSQL. The rest of saving is covered end to end by the intake flow tests. Each test's rows
 * are rolled back afterwards.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
class PostgresInvoiceStoreTest {

  private static final String FINANCIAL_YEAR = "2026-27";
  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private static final InvoicePayload INVOICE =
      JSON.convertValue(tree(DOMESTIC_PAYLOAD), InvoicePayload.class);
  private static final InvoiceNumber NUMBER =
      new InvoiceNumber(
          new SeriesKey(
              "TEST_INTAKE",
              "subscriptions",
              FinancialYear.of(INVOICE.invoiceDate()),
              new Shard(0)),
          142,
          INVOICE.invoiceNumber());

  @Autowired private JdbcClient jdbcClient;

  /** The trace the store sees as current, which a test may change. */
  private Map<String, String> currentTrace = Map.of();

  private PostgresInvoiceStore store;

  @BeforeEach
  void createStore() {
    store = new PostgresInvoiceStore(jdbcClient, JSON, () -> currentTrace);
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

  @Test
  void savesTheCurrentTraceWithTheRenderMessage() throws JsonProcessingException {
    currentTrace = Map.of("traceparent", TRACEPARENT);

    store.saveNumbered(INVOICE, NUMBER);

    assertThat(savedTraceHeaders()).isEqualTo(Map.of("traceparent", TRACEPARENT));
  }

  @Test
  void savesNoTraceHeadersOutsideATrace() throws JsonProcessingException {
    store.saveNumbered(INVOICE, NUMBER);

    assertThat(savedTraceHeaders()).isEmpty();
  }

  private Map<String, String> savedTraceHeaders() throws JsonProcessingException {
    String headers =
        jdbcClient
            .sql("SELECT headers::text FROM outbox WHERE invoice_id = :invoiceId")
            .param("invoiceId", INVOICE.invoiceId())
            .query(String.class)
            .single();
    return JSON.readValue(headers, new TypeReference<>() {});
  }
}
