package com.shashank.invoicekit.invoice.domain.entity;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.EXPORT_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.JSON;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks how an {@code invoice.requested} message is read into the event model. */
class InvoiceRequestedTest {

  @Test
  void readsTheBuKeyAsTheBusinessUnit() throws JsonProcessingException {
    InvoiceRequested event = JSON.readValue("{\"bu\": \"subscriptions\"}", InvoiceRequested.class);

    assertThat(event.businessUnit()).isEqualTo("subscriptions");
  }

  @Test
  void absentCollectionsBecomeEmpty() throws JsonProcessingException {
    InvoiceRequested event = JSON.readValue("{\"schemaVersion\": 2}", InvoiceRequested.class);

    assertThat(event.lineItems()).isEmpty();
    assertThat(event.attributes()).isEmpty();
  }

  @Test
  void keepsDecimalsExactlyAsSent() {
    LineItem line = event(DOMESTIC_EVENT).lineItems().getFirst();

    assertThat(line.quantity().toPlainString()).isEqualTo("10");
    assertThat(line.unitPrice().toPlainString()).isEqualTo("1500.0000");
  }

  @Test
  void attributesKeepTheirOrder() {
    assertThat(event(DOMESTIC_EVENT).attributes())
        .containsExactly(
            Map.entry("subscriptionId", "sub-482"),
            Map.entry("planName", "Growth"),
            Map.entry("billingCycle", "MONTHLY"),
            Map.entry("couponCode", "WELCOME10"));
  }

  @Test
  void inrWithoutAnExchangeRateConvertsAtOne() {
    assertThat(event(DOMESTIC_EVENT).inrExchangeRate()).isEqualTo(BigDecimal.ONE);
  }

  @Test
  void foreignCurrencyConvertsAtTheRateSent() {
    assertThat(event(EXPORT_EVENT).inrExchangeRate()).isEqualTo(new BigDecimal("85.0000"));
  }

  @Test
  void knowsWhetherItIsAnExport() {
    assertThat(event(EXPORT_EVENT).isExport()).isTrue();
    assertThat(event(DOMESTIC_EVENT).isExport()).isFalse();
  }
}
