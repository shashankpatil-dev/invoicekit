package com.shashank.invoicekit.invoice.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the storage layout of invoice PDFs. */
class DocumentPathTest {

  private static final UUID INVOICE_ID = UUID.fromString("0190f3a2-7c1e-7b4d-9a6f-2e8c1d5b3a70");

  @Test
  void groupsByBusinessUnitAndInvoiceDate() {
    DocumentPath path = new DocumentPath("subscriptions", LocalDate.of(2026, 11, 21), INVOICE_ID);

    assertThat(path.value())
        .isEqualTo("invoices/subscriptions/2026/11/21/0190f3a2-7c1e-7b4d-9a6f-2e8c1d5b3a70.pdf");
  }

  @Test
  void padsMonthAndDayToTwoDigits() {
    DocumentPath path = new DocumentPath("subscriptions", LocalDate.of(2027, 3, 5), INVOICE_ID);

    assertThat(path.value()).startsWith("invoices/subscriptions/2027/03/05/");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectsAMissingBusinessUnit(String businessUnit) {
    assertThatThrownBy(() -> new DocumentPath(businessUnit, LocalDate.of(2026, 11, 1), INVOICE_ID))
        .hasMessageContaining("businessUnit is required");
  }

  @Test
  void rejectsAMissingInvoiceDate() {
    assertThatThrownBy(() -> new DocumentPath("subscriptions", null, INVOICE_ID))
        .hasMessageContaining("invoiceDate is required");
  }

  @Test
  void rejectsAMissingInvoiceId() {
    assertThatThrownBy(() -> new DocumentPath("subscriptions", LocalDate.of(2026, 11, 1), null))
        .hasMessageContaining("invoiceId is required");
  }
}
