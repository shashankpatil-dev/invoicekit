package com.shashank.invoicekit.invoice.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks that a render job is complete, and what it asks the engine and storage for. */
class RenderJobTest {

  private static final UUID INVOICE_ID = UUID.fromString("0190f3a2-7c1e-7b4d-9a6f-2e8c1d5b3a70");
  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 1);
  private static final String NUMBER = "SUBA260000000001";
  private static final String PAYLOAD = "{}";

  @Test
  void asksTheEngineForItsPayloadWithItsBusinessUnitsTemplate() {
    RenderJob job =
        new RenderJob(INVOICE_ID, "subscriptions", "2026-27", INVOICE_DATE, NUMBER, PAYLOAD);

    assertThat(job.renderRequest()).isEqualTo(new RenderRequest("subscriptions", PAYLOAD));
  }

  @Test
  void storesItsPdfUnderItsBusinessUnitAndInvoiceDate() {
    RenderJob job =
        new RenderJob(INVOICE_ID, "subscriptions", "2026-27", INVOICE_DATE, NUMBER, PAYLOAD);

    assertThat(job.documentPath())
        .isEqualTo(new DocumentPath("subscriptions", INVOICE_DATE, INVOICE_ID));
  }

  @Test
  void rejectsAMissingInvoiceId() {
    assertThatThrownBy(
            () -> new RenderJob(null, "subscriptions", "2026-27", INVOICE_DATE, NUMBER, PAYLOAD))
        .hasMessageContaining("invoiceId is required");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectsAMissingBusinessUnit(String businessUnit) {
    assertThatThrownBy(
            () -> new RenderJob(INVOICE_ID, businessUnit, "2026-27", INVOICE_DATE, NUMBER, PAYLOAD))
        .hasMessageContaining("businessUnit is required");
  }

  /** The financial year picks the partition, so it must be in its stored form. */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"2026", "2026-28"})
  void rejectsAFinancialYearThatIsNotAPartitionKey(String financialYear) {
    assertThatThrownBy(
            () ->
                new RenderJob(
                    INVOICE_ID, "subscriptions", financialYear, INVOICE_DATE, NUMBER, PAYLOAD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Financial year");
  }

  @Test
  void rejectsAMissingInvoiceDate() {
    assertThatThrownBy(
            () -> new RenderJob(INVOICE_ID, "subscriptions", "2026-27", null, NUMBER, PAYLOAD))
        .hasMessageContaining("invoiceDate is required");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectsAMissingInvoiceNumber(String invoiceNumber) {
    assertThatThrownBy(
            () ->
                new RenderJob(
                    INVOICE_ID, "subscriptions", "2026-27", INVOICE_DATE, invoiceNumber, PAYLOAD))
        .hasMessageContaining("invoiceNumber is required");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectsAMissingPayload(String payloadJson) {
    assertThatThrownBy(
            () ->
                new RenderJob(
                    INVOICE_ID, "subscriptions", "2026-27", INVOICE_DATE, NUMBER, payloadJson))
        .hasMessageContaining("payloadJson is required");
  }
}
