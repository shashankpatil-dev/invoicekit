package com.shashank.invoicekit.invoice.domain.entity;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.MAHARASHTRA;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.SAAS_18;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import org.junit.jupiter.api.Test;

/** Checks how the invoice's reference data answers for tax codes. */
class InvoiceContextTest {

  @Test
  void findsTheRateForATaxCode() {
    assertThat(context(MAHARASHTRA).taxRateFor("SAAS_18")).isEqualTo(SAAS_18);
  }

  @Test
  void rejectsATaxCodeWithoutARate() {
    assertThatThrownBy(() -> context(MAHARASHTRA).taxRateFor("SAAS_99"))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
              assertThat(rejected.reasons())
                  .containsExactly("taxCode SAAS_99 has no rate on the invoice date");
            });
  }
}
