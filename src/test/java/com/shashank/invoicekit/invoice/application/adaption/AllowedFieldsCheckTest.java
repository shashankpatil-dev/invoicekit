package com.shashank.invoicekit.invoice.application.adaption;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.domesticEvent;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.draftOf;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.event;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.subscriptionsConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceField;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks that a BU can send only the optional fields its config allows. */
class AllowedFieldsCheckTest {

  private static final AdapterConfig NOTHING_ALLOWED =
      new AdapterConfig("subscriptions", 1, Set.of(), NumberFormat.parse("SUB{seq:10}"));

  private final AllowedFieldsCheck check = new AllowedFieldsCheck();

  @Test
  void acceptsFieldsTheConfigAllows() {
    assertThatCode(() -> check.check(draftOf(event(DOMESTIC_EVENT)), subscriptionsConfig()))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsADraftWithNoOptionalFields() {
    InvoiceDraft bare =
        draftOf(
            domesticEvent(
                json -> {
                  json.remove("dueDate");
                  json.remove("paymentTerms");
                  json.remove("attributes");
                }));

    assertThatCode(() -> check.check(bare, NOTHING_ALLOWED)).doesNotThrowAnyException();
  }

  @Test
  void rejectsEveryFieldTheConfigDoesNotAllow() {
    InvoiceDraft draft = draftOf(domesticEvent(json -> json.put("poNumber", "PO-1")));

    assertRejected(
        draft,
        NOTHING_ALLOWED,
        "dueDate is not allowed for business unit subscriptions",
        "paymentTerms is not allowed for business unit subscriptions",
        "poNumber is not allowed for business unit subscriptions",
        "subscriptionId is not allowed for business unit subscriptions",
        "planName is not allowed for business unit subscriptions",
        "billingCycle is not allowed for business unit subscriptions",
        "couponCode is not allowed for business unit subscriptions");
  }

  @Test
  void rejectsUnknownAttributesAlongsideDisallowedFields() {
    InvoiceDraft draft =
        draftOf(
            domesticEvent(
                json -> {
                  json.put("poNumber", "PO-1");
                  json.withObjectProperty("attributes").put("discountCode", "X").put("seats", "4");
                }));

    assertRejected(
        draft,
        subscriptionsConfig(),
        "attribute discountCode is not a known invoice field",
        "attribute seats is not a known invoice field",
        "poNumber is not allowed for business unit subscriptions");
  }

  @Test
  void configAllowsOnlyItsListedFields() {
    assertThat(subscriptionsConfig().allows(InvoiceField.PLAN_NAME)).isTrue();
    assertThat(subscriptionsConfig().allows(InvoiceField.MILESTONE)).isFalse();
  }

  private void assertRejected(InvoiceDraft draft, AdapterConfig config, String... reasons) {
    assertThatThrownBy(() -> check.check(draft, config))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
              assertThat(rejected.reasons()).containsExactly(reasons);
            });
  }
}
