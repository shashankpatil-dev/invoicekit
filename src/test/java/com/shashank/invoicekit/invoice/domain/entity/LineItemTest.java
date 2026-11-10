package com.shashank.invoicekit.invoice.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks the values a line works out from its own fields. */
class LineItemTest {

  private static final LocalDate START = LocalDate.of(2026, 11, 1);
  private static final LocalDate END = LocalDate.of(2026, 11, 30);

  @ParameterizedTest
  @CsvSource({
    "10, 1500.0000, 1500.00, 13500.00",
    "20, 50.0000, 0.00, 1000.00",
    "1, 10.0050, 0.00, 10.01",
    "1, 10.0049, 0.00, 10.00",
    "2.5, 3.3333, 0.00, 8.33"
  })
  void taxableValueIsQuantityTimesPriceLessDiscountRoundedHalfUp(
      String quantity, String unitPrice, String discount, String taxableValue) {
    LineItem line = line(quantity, unitPrice, discount, START, END);

    assertThat(line.taxableValue()).isEqualByComparingTo(taxableValue);
  }

  @Test
  void anAbsentDiscountIsZero() {
    assertThat(line("1", "1", null, START, END).discount()).isEqualByComparingTo("0");
  }

  @Test
  void grossValueIsQuantityTimesPrice() {
    assertThat(line("3", "2.5000", "1.00", START, END).grossValue()).isEqualByComparingTo("7.5");
  }

  @Test
  void aServicePeriodNeedsBothDays() {
    assertThat(line("1", "1", null, START, END).hasServicePeriod()).isTrue();
    assertThat(line("1", "1", null, null, END).hasServicePeriod()).isFalse();
    assertThat(line("1", "1", null, START, null).hasServicePeriod()).isFalse();
  }

  private static LineItem line(
      String quantity, String unitPrice, String discount, LocalDate start, LocalDate end) {
    return new LineItem(
        "Plan",
        "SAAS_18",
        new BigDecimal(quantity),
        "NOS",
        new BigDecimal(unitPrice),
        discount == null ? null : new BigDecimal(discount),
        start,
        end);
  }
}
