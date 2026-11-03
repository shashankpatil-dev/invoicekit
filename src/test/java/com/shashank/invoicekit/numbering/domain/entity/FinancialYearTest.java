package com.shashank.invoicekit.numbering.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the April to March financial year and its stored label. */
class FinancialYearTest {

  @ParameterizedTest
  @CsvSource({
    "2026-04-01, 2026-27",
    "2026-12-31, 2026-27",
    "2027-01-15, 2026-27",
    "2027-03-31, 2026-27",
    "2027-04-01, 2027-28"
  })
  void yearRunsFromAprilToMarch(LocalDate date, String label) {
    assertThat(FinancialYear.of(date).label()).isEqualTo(label);
  }

  @ParameterizedTest
  @CsvSource({"2026, 2026-27, 26", "2005, 2005-06, 05", "2099, 2099-00, 99"})
  void labelAndShortCodeUseTheStartingYear(int startYear, String label, String shortCode) {
    FinancialYear year = new FinancialYear(startYear);

    assertThat(year.label()).isEqualTo(label);
    assertThat(year.shortCode()).isEqualTo(shortCode);
    assertThat(year).hasToString(label);
  }

  @Test
  void parseReadsBackTheLabel() {
    FinancialYear year = FinancialYear.parse("2026-27");

    assertThat(year).isEqualTo(new FinancialYear(2026));
    assertThat(year.label()).isEqualTo("2026-27");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"2026", "26-27", "2026-2027", "2026/27", " 2026-27", "2026-27 "})
  void parseRejectsLabelsThatAreNotCanonical(String label) {
    assertThatThrownBy(() -> FinancialYear.parse(label))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must look like 2026-27");
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-28", "2026-26", "2026-00"})
  void parseRejectsASecondYearThatIsNotTheNextYear(String label) {
    assertThatThrownBy(() -> FinancialYear.parse(label))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must end in the year after it starts, as in 2026-27");
  }

  @ParameterizedTest
  @ValueSource(ints = {999, 10000})
  void startYearMustHaveFourDigits(int startYear) {
    assertThatThrownBy(() -> new FinancialYear(startYear))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("four-digit year");
  }
}
