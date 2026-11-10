package com.shashank.invoicekit.invoice.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks that a rate applies only between its dates, both included. */
class TaxRateTest {

  private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
  private static final LocalDate TO = LocalDate.of(2027, 3, 31);

  @ParameterizedTest
  @CsvSource({
    "2026-03-31, false",
    "2026-04-01, true",
    "2026-11-01, true",
    "2027-03-31, true",
    "2027-04-01, false"
  })
  void appliesFromItsFirstDayToItsLastDay(LocalDate date, boolean effective) {
    assertThat(rate(FROM, TO).isEffectiveOn(date)).isEqualTo(effective);
  }

  @Test
  void aCurrentRateHasNoLastDay() {
    assertThat(rate(FROM, null).isEffectiveOn(LocalDate.of(2099, 1, 1))).isTrue();
  }

  @Test
  void anAbsentCessRateIsZero() {
    assertThat(rate(FROM, null).cessRate()).isEqualByComparingTo("0");
  }

  private static TaxRate rate(LocalDate from, LocalDate to) {
    return new TaxRate("SAAS_18", "998314", new BigDecimal("18.00"), null, from, to);
  }
}
