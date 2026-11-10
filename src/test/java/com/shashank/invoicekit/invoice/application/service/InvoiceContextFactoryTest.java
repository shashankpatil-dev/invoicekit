package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.EXPORT_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.MAHARASHTRA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shashank.invoicekit.invoice.application.port.outbound.FakeReferenceData;
import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceContext;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import org.junit.jupiter.api.Test;

/** Checks the context holds exactly the reference data the draft needs, on its invoice date. */
class InvoiceContextFactoryTest {

  private final FakeReferenceData referenceData = FakeReferenceData.supersetExample();
  private final InvoiceContextFactory factory = new InvoiceContextFactory(referenceData);

  @Test
  void loadsTheSupplierStateRatesAndDeclarationsForTheDomesticExample() {
    assertThat(contextFor(IntakeFixtures.domesticEvent(event -> {})))
        .isEqualTo(IntakeFixtures.context(MAHARASHTRA));
  }

  @Test
  void leavesThePlaceOfSupplyStateEmptyForAnExport() {
    assertThat(contextFor(IntakeFixtures.event(EXPORT_EVENT)).placeOfSupplyState()).isNull();
  }

  @Test
  void leavesAnUnknownPlaceOfSupplyStateEmpty() {
    InvoiceRequested event =
        IntakeFixtures.domesticEvent(changed -> changed.put("placeOfSupplyStateCode", "99"));

    assertThat(contextFor(event).placeOfSupplyState()).isNull();
  }

  @Test
  void leavesOutATaxCodeWithNoRateOnTheInvoiceDate() {
    InvoiceRequested event =
        IntakeFixtures.domesticEvent(changed -> changed.put("invoiceDate", "2017-06-30"));

    assertThat(contextFor(event).taxRates()).isEmpty();
  }

  @Test
  void leavesOutAnUnknownTaxCode() {
    InvoiceRequested event =
        IntakeFixtures.domesticEvent(
            changed -> ((ObjectNode) changed.get("lineItems").get(0)).put("taxCode", "GOODS_5"));

    assertThat(contextFor(event).taxRates()).containsOnlyKeys("SAAS_18");
  }

  @Test
  void rejectsAnEntityThatNoLongerExists() {
    referenceData.withoutEntity("ACME_MH");

    assertThatThrownBy(() -> contextFor(IntakeFixtures.domesticEvent(event -> {})))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> assertThat(rejected.code()).isEqualTo(RejectionCode.ENTITY_NOT_ALLOWED));
  }

  private InvoiceContext contextFor(InvoiceRequested event) {
    return factory.contextFor(IntakeFixtures.draftOf(event), IntakeFixtures.subscriptionsConfig());
  }
}
