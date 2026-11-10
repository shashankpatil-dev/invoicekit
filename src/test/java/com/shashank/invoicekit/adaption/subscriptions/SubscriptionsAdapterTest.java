package com.shashank.invoicekit.adaption.subscriptions;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.domesticEvent;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the {@code subscriptions} BU's own rules and its pass-through mapping. */
class SubscriptionsAdapterTest {

  private final SubscriptionsAdapter adapter = new SubscriptionsAdapter();

  @Test
  void servesTheSubscriptionsBusinessUnit() {
    assertThat(adapter.businessUnit()).isEqualTo("subscriptions");
  }

  @Test
  void passesLinesAndAttributesThrough() {
    InvoiceRequested event = event(DOMESTIC_EVENT);

    InvoiceDraft draft = adapter.adapt(event);

    assertThat(draft).isEqualTo(new InvoiceDraft(event, event.lineItems(), event.attributes()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"MONTHLY", "ANNUAL"})
  void acceptsTheBillingCyclesItSells(String billingCycle) {
    InvoiceRequested event =
        domesticEvent(json -> attributes(json).put("billingCycle", billingCycle));

    assertThatCode(() -> adapter.adapt(event)).doesNotThrowAnyException();
  }

  @Test
  void acceptsAnEventWithoutABillingCycle() {
    InvoiceRequested event = domesticEvent(json -> attributes(json).remove("billingCycle"));

    assertThatCode(() -> adapter.adapt(event)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"WEEKLY", "monthly", ""})
  void rejectsAnyOtherBillingCycle(String billingCycle) {
    InvoiceRequested event =
        domesticEvent(json -> attributes(json).put("billingCycle", billingCycle));

    assertRejected(event, "billingCycle must be MONTHLY or ANNUAL, but was " + billingCycle);
  }

  @ParameterizedTest
  @ValueSource(strings = {"servicePeriodStart", "servicePeriodEnd"})
  void rejectsALineWithoutAFullServicePeriod(String missing) {
    InvoiceRequested event = domesticEvent(json -> line(json, 1).remove(missing));

    assertRejected(event, "line 2 needs servicePeriodStart and servicePeriodEnd");
  }

  @Test
  void reportsEveryProblemTogether() {
    InvoiceRequested event =
        domesticEvent(
            json -> {
              attributes(json).put("billingCycle", "WEEKLY");
              line(json, 0).remove("servicePeriodStart");
              line(json, 1).remove("servicePeriodEnd");
            });

    assertRejected(
        event,
        "billingCycle must be MONTHLY or ANNUAL, but was WEEKLY",
        "line 1 needs servicePeriodStart and servicePeriodEnd",
        "line 2 needs servicePeriodStart and servicePeriodEnd");
  }

  private void assertRejected(InvoiceRequested event, String... reasons) {
    assertThatThrownBy(() -> adapter.adapt(event))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
              assertThat(rejected.reasons()).containsExactly(reasons);
            });
  }

  private static ObjectNode attributes(ObjectNode json) {
    return json.withObjectProperty("attributes");
  }

  private static ObjectNode line(ObjectNode json, int index) {
    return (ObjectNode) json.withArrayProperty("lineItems").get(index);
  }
}
