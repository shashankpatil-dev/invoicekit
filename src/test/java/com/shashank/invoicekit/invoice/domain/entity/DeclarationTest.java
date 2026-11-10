package com.shashank.invoicekit.invoice.domain.entity;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks when a declaration applies to the domestic example: a registered recipient, no reverse
 * charge, BU {@code subscriptions}, dated 1 November 2026.
 */
class DeclarationTest {

  private static final InvoiceRequested INVOICE = event(DOMESTIC_EVENT);
  private static final LocalDate GST_START = LocalDate.of(2017, 7, 1);

  @Test
  void anEmptyConditionMatchesEveryInvoice() {
    assertThat(declaration(null, null, null, null).appliesTo(INVOICE)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"subscriptions, true", "usage, false"})
  void businessUnitMustMatch(String businessUnit, boolean applies) {
    assertThat(declaration(businessUnit, null, null, null).appliesTo(INVOICE)).isEqualTo(applies);
  }

  @ParameterizedTest
  @CsvSource({"DOMESTIC, true", "EXPORT_LUT, false"})
  void supplyCategoryMustMatch(SupplyCategory supplyCategory, boolean applies) {
    assertThat(declaration(null, supplyCategory, null, null).appliesTo(INVOICE)).isEqualTo(applies);
  }

  @ParameterizedTest
  @CsvSource({"false, true", "true, false"})
  void reverseChargeMustMatch(Boolean reverseCharge, boolean applies) {
    assertThat(declaration(null, null, reverseCharge, null).appliesTo(INVOICE)).isEqualTo(applies);
  }

  @ParameterizedTest
  @CsvSource({"true, true", "false, false"})
  void recipientRegistrationMustMatch(Boolean recipientRegistered, boolean applies) {
    assertThat(declaration(null, null, null, recipientRegistered).appliesTo(INVOICE))
        .isEqualTo(applies);
  }

  @Test
  void everyFilledConditionMustMatch() {
    assertThat(
            declaration("subscriptions", SupplyCategory.DOMESTIC, false, true).appliesTo(INVOICE))
        .isTrue();
    assertThat(declaration("subscriptions", SupplyCategory.DOMESTIC, true, true).appliesTo(INVOICE))
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "2026-11-02, , false",
    "2017-07-01, 2026-10-31, false",
    "2017-07-01, 2026-11-01, true",
    "2026-11-01, , true"
  })
  void appliesOnlyOnItsDates(LocalDate effectiveFrom, LocalDate effectiveTo, boolean applies) {
    Declaration declaration =
        new Declaration("GENERAL", "Text", null, null, null, null, 10, effectiveFrom, effectiveTo);

    assertThat(declaration.appliesTo(INVOICE)).isEqualTo(applies);
  }

  private static Declaration declaration(
      String businessUnit,
      SupplyCategory supplyCategory,
      Boolean reverseCharge,
      Boolean recipientRegistered) {
    return new Declaration(
        "CODE",
        "Text",
        businessUnit,
        supplyCategory,
        reverseCharge,
        recipientRegistered,
        10,
        GST_START,
        null);
  }
}
