package com.shashank.invoicekit.invoice.domain.entity;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.domesticEvent;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.draftOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the optional field catalog and how it finds fields in a draft. */
class InvoiceFieldTest {

  @ParameterizedTest
  @CsvSource({
    "subscriptionId, SUBSCRIPTION_ID",
    "planName, PLAN_NAME",
    "billingCycle, BILLING_CYCLE",
    "couponCode, COUPON_CODE",
    "usageSummary, USAGE_SUMMARY",
    "contractRef, CONTRACT_REF",
    "milestone, MILESTONE",
    "addonId, ADDON_ID",
    "developerId, DEVELOPER_ID"
  })
  void findsAttributeFieldsByTheirKey(String attributeKey, InvoiceField field) {
    assertThat(InvoiceField.forAttributeKey(attributeKey)).contains(field);
    assertThat(field.attributeKey()).contains(attributeKey);
  }

  @ParameterizedTest
  @EnumSource(names = {"DUE_DATE", "PAYMENT_TERMS", "PO_NUMBER"})
  void topLevelFieldsAreNotAttributes(InvoiceField field) {
    assertThat(field.attributeKey()).isEmpty();
    assertThat(InvoiceField.forAttributeKey(field.key())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"discountCode", "PLAN_NAME", ""})
  void unknownAttributeKeysFindNothing(String attributeKey) {
    assertThat(InvoiceField.forAttributeKey(attributeKey)).isEmpty();
  }

  @Test
  void topLevelFieldsAreUsedWhenTheEventHasAValue() {
    InvoiceDraft draft = draftOf(domesticEvent(event -> event.put("poNumber", "PO-1")));
    InvoiceDraft bare =
        draftOf(domesticEvent(event -> event.remove(List.of("dueDate", "paymentTerms"))));

    assertThat(InvoiceField.DUE_DATE.isUsedBy(draft)).isTrue();
    assertThat(InvoiceField.PAYMENT_TERMS.isUsedBy(draft)).isTrue();
    assertThat(InvoiceField.PO_NUMBER.isUsedBy(draft)).isTrue();
    assertThat(InvoiceField.DUE_DATE.isUsedBy(bare)).isFalse();
    assertThat(InvoiceField.PAYMENT_TERMS.isUsedBy(bare)).isFalse();
    assertThat(InvoiceField.PO_NUMBER.isUsedBy(bare)).isFalse();
  }

  @Test
  void attributeFieldsAreUsedWhenTheDraftHasTheirKey() {
    InvoiceRequested event = domesticEvent(change -> {});
    InvoiceDraft draft = new InvoiceDraft(event, event.lineItems(), Map.of("planName", "Growth"));

    assertThat(InvoiceField.PLAN_NAME.isUsedBy(draft)).isTrue();
    assertThat(InvoiceField.SUBSCRIPTION_ID.isUsedBy(draft)).isFalse();
  }
}
