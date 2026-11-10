package com.shashank.invoicekit.invoice.flow;

import static com.shashank.invoicekit.invoice.flow.IntakeEvents.BUSINESS_UNIT;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.ENTITY_CODE;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.FINANCIAL_YEAR;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.NUMBER_FORMAT;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.newEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import com.shashank.invoicekit.support.SeriesReconciliation;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sends events intake must reject and checks each gets the right code without using a number or
 * claiming its event, as docs/INTAKE.md section 8 lists.
 */
@IntakeFlowTest
class IntakeRejectionTest {

  @Autowired private IntakeService intake;
  @Autowired private IntakeRows rows;
  @Autowired private SeriesReconciliation reconciliation;

  @AfterEach
  void deleteRows() {
    rows.deleteAll();
  }

  @Test
  void eventFromABusinessUnitWithNoAdapterIsUnknown() {
    assertRejectedWithoutANumber(
        newEvent(event -> event.put("bu", "marketplace")), RejectionCode.UNKNOWN_BU);
  }

  @Test
  void eventFromAnEntityTheBusinessUnitMayNotUseIsNotAllowed() {
    assertRejectedWithoutANumber(
        newEvent(event -> event.put("entityCode", "TEST_NUMBERING")),
        RejectionCode.ENTITY_NOT_ALLOWED);
  }

  @Test
  void eventWithAnAttributeTheBusinessUnitMayNotSendFailsValidation() {
    assertRejectedWithoutANumber(
        newEvent(event -> ((ObjectNode) event.get("attributes")).put("contractRef", "C-1")),
        RejectionCode.VALIDATION_FAILED);
  }

  @Test
  void eventWithAnUnknownTaxCodeFailsValidation() {
    assertRejectedWithoutANumber(
        newEvent(event -> ((ObjectNode) event.get("lineItems").get(0)).put("taxCode", "GOODS_5")),
        RejectionCode.VALIDATION_FAILED);
  }

  @Test
  void eventWithAnUnknownPlaceOfSupplyStateFailsValidation() {
    assertRejectedWithoutANumber(
        newEvent(event -> event.put("placeOfSupplyStateCode", "99")),
        RejectionCode.VALIDATION_FAILED);
  }

  @Test
  void eventForAFullSeriesIsRejectedAndItsClaimRolledBack() {
    rows.fillSeries();

    assertRejected(newEvent(), RejectionCode.SERIES_EXHAUSTED);
    assertThat(rows.count("invoice_event")).isZero();
    assertThat(rows.count("invoice")).isZero();
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR))
        .isEqualTo(Map.of("A", NUMBER_FORMAT.maxSequence()));
  }

  private void assertRejectedWithoutANumber(InvoiceRequested event, RejectionCode code) {
    assertRejected(event, code);
    assertThat(rows.count("invoice_counter")).isZero();
    assertThat(rows.count("invoice_event")).isZero();
    assertThat(rows.count("invoice")).isZero();
  }

  private void assertRejected(InvoiceRequested event, RejectionCode code) {
    assertThatThrownBy(() -> intake.process(event))
        .isInstanceOfSatisfying(
            RejectedEventException.class, rejected -> assertThat(rejected.code()).isEqualTo(code));
  }
}
