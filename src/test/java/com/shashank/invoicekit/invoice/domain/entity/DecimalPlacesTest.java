package com.shashank.invoicekit.invoice.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks the contract's decimal places and half-up rounding. */
class DecimalPlacesTest {

  @ParameterizedTest
  @CsvSource({"0.005, 0.01", "0.004, 0.00", "1.235, 1.24", "-0.005, -0.01", "7, 7.00"})
  void moneyRoundsHalfUpToPaise(String value, String rounded) {
    assertThat(DecimalPlaces.MONEY.format(new BigDecimal(value))).isEqualTo(rounded);
  }

  @ParameterizedTest
  @CsvSource({
    "MONEY, 1215, 1215.00",
    "RATE, 9, 9.00",
    "UNIT_PRICE, 50, 50.0000",
    "EXCHANGE_RATE, 1, 1.0000",
    "QUANTITY, 2.5, 2.500"
  })
  void formatWritesEveryPlace(DecimalPlaces places, String value, String formatted) {
    assertThat(places.format(new BigDecimal(value))).isEqualTo(formatted);
  }

  @ParameterizedTest
  @CsvSource({
    "QUANTITY, 1.234, true",
    "QUANTITY, 1.2340000, true",
    "QUANTITY, 1.2345, false",
    "UNIT_PRICE, 1.2345, true",
    "UNIT_PRICE, 1.23456, false",
    "MONEY, 100, true",
    "MONEY, 1E+3, true",
    "MONEY, 0.005, false"
  })
  void allowsIgnoresTrailingZeros(DecimalPlaces places, String value, boolean allowed) {
    assertThat(places.allows(new BigDecimal(value))).isEqualTo(allowed);
  }

  @Test
  void roundKeepsTheValueAsANumber() {
    assertThat(DecimalPlaces.RATE.round(new BigDecimal("0.125"))).isEqualTo(new BigDecimal("0.13"));
  }
}
