package com.shashank.invoicekit.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.application.adaption.AdapterRegistry;
import com.shashank.invoicekit.invoice.application.adaption.AllowedFieldsCheck;
import com.shashank.invoicekit.invoice.application.adaption.StubAdapter;
import com.shashank.invoicekit.invoice.application.port.outbound.FakeReferenceData;
import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the one rejection the PostgreSQL flow tests can't reach: the startup check makes sure
 * every adapter has a config, so a config can only go missing while intake runs.
 */
class IntakeServiceTest {

  @Test
  void rejectsABusinessUnitWhoseConfigWasDeactivatedAfterStartup() {
    FakeReferenceData referenceData =
        FakeReferenceData.supersetExample().withoutConfig("subscriptions");
    // The event is rejected before the size check and the transaction, so neither is needed.
    IntakeService intake =
        new IntakeService(
            new InvoiceRequestValidator(),
            new AdapterRegistry(List.of(new StubAdapter("subscriptions"))),
            referenceData,
            new AllowedFieldsCheck(),
            new InvoiceContextFactory(referenceData),
            new InvoiceAssembler(),
            null,
            null);

    assertThatThrownBy(() -> intake.process(IntakeFixtures.domesticEvent(event -> {})))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.UNKNOWN_BU);
              assertThat(rejected.reasons())
                  .containsExactly("business unit subscriptions has no active config");
            });
  }
}
