package com.shashank.invoicekit.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.invoice.domain.entity.AppliedRates;
import com.shashank.invoicekit.invoice.domain.entity.LineItem;
import com.shashank.invoicekit.invoice.domain.entity.SupplyType;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks tax per line, the half-up rounding rule and how totals add up. */
class TaxCalculatorTest {

  private static final TaxRate GST_18 = rate("18.00", "0");
  private static final TaxRate GST_28_CESS_12 = rate("28.00", "12.00");

  private final TaxCalculator calculator = new TaxCalculator();

  @Test
  void intraStateSplitsGstIntoEqualCgstAndSgst() {
    LineTax tax = calculator.calculate(line("10", "1500", "1500"), GST_18, SupplyType.INTRA_STATE);

    assertThat(tax.rates())
        .isEqualTo(new AppliedRates(dec("18.00"), dec("9.00"), dec("9.00"), dec("0"), dec("0")));
    assertAmounts(tax.amounts(), "13500.00", "1215.00", "1215.00", "0.00", "0.00");
  }

  @Test
  void interStateChargesTheFullRateAsIgst() {
    LineTax tax = calculator.calculate(line("10", "1500", "1500"), GST_18, SupplyType.INTER_STATE);

    assertThat(tax.rates())
        .isEqualTo(new AppliedRates(dec("18.00"), dec("0"), dec("0"), dec("18.00"), dec("0")));
    assertAmounts(tax.amounts(), "13500.00", "0.00", "0.00", "2430.00", "0.00");
  }

  @Test
  void exportChargesNoGstOrCess() {
    LineTax tax = calculator.calculate(line("10", "100", "0"), GST_28_CESS_12, SupplyType.EXPORT);

    assertThat(tax.rates()).isEqualTo(AppliedRates.NONE);
    assertAmounts(tax.amounts(), "1000.00", "0.00", "0.00", "0.00", "0.00");
  }

  @Test
  void cessIsChargedOnTheTaxableValue() {
    LineTax tax =
        calculator.calculate(line("1", "1000", "0"), GST_28_CESS_12, SupplyType.INTER_STATE);

    assertAmounts(tax.amounts(), "1000.00", "0.00", "0.00", "280.00", "120.00");
    assertThat(tax.amounts().total()).isEqualByComparingTo("1400.00");
  }

  @Test
  void copiesTheSacCode() {
    LineTax tax = calculator.calculate(line("1", "1", "0"), GST_18, SupplyType.INTRA_STATE);

    assertThat(tax.hsnCode()).isEqualTo("998314");
  }

  @ParameterizedTest
  @CsvSource({"0.2500, 0.05", "0.2400, 0.04", "10.0000, 1.80"})
  void taxRoundsHalfUpAtHalfAPaisa(String unitPrice, String igst) {
    LineTax tax = calculator.calculate(line("1", unitPrice, "0"), GST_18, SupplyType.INTER_STATE);

    assertThat(tax.amounts().igst()).isEqualByComparingTo(igst);
  }

  @Test
  void taxIsChargedOnTheRoundedTaxableValue() {
    LineTax tax = calculator.calculate(line("1", "10.0050", "0"), GST_18, SupplyType.INTER_STATE);

    assertThat(tax.amounts().taxableValue()).isEqualByComparingTo("10.01");
    assertThat(tax.amounts().igst()).isEqualByComparingTo("1.80");
  }

  @Test
  void cgstAndSgstAreEachRoundedOnTheirOwn() {
    LineTax tax = calculator.calculate(line("1", "10.25", "0"), GST_18, SupplyType.INTRA_STATE);

    assertAmounts(tax.amounts(), "10.25", "0.92", "0.92", "0.00", "0.00");
    assertThat(tax.amounts().tax()).isEqualByComparingTo("1.84");
  }

  @Test
  void totalsAddTheRoundedLineValues() {
    LineTax first = calculator.calculate(line("1", "10.25", "0"), GST_18, SupplyType.INTRA_STATE);
    LineTax second = calculator.calculate(line("1", "10.25", "0"), GST_18, SupplyType.INTRA_STATE);

    TaxAmounts totals = calculator.total(List.of(first, second));

    assertAmounts(totals, "20.50", "1.84", "1.84", "0.00", "0.00");
    assertThat(totals.tax()).isEqualByComparingTo("3.68");
    assertThat(totals.total()).isEqualByComparingTo("24.18");
  }

  @Test
  void totalOfNoLinesIsZero() {
    assertAmounts(calculator.total(List.of()), "0", "0", "0", "0", "0");
  }

  @ParameterizedTest
  @CsvSource({
    "1000.00, 85.0000, 85000.00",
    "10.01, 83.3333, 834.17",
    "17110.00, 1, 17110.00",
    "0.01, 0.4999, 0.00"
  })
  void inrConvertsTheRoundedTotalAndRoundsAgain(String amount, String rate, String inr) {
    assertThat(calculator.inInr(dec(amount), dec(rate))).isEqualTo(dec(inr));
  }

  private static void assertAmounts(
      TaxAmounts amounts, String taxableValue, String cgst, String sgst, String igst, String cess) {
    assertThat(amounts.taxableValue()).isEqualByComparingTo(taxableValue);
    assertThat(amounts.cgst()).isEqualByComparingTo(cgst);
    assertThat(amounts.sgst()).isEqualByComparingTo(sgst);
    assertThat(amounts.igst()).isEqualByComparingTo(igst);
    assertThat(amounts.cess()).isEqualByComparingTo(cess);
  }

  private static LineItem line(String quantity, String unitPrice, String discount) {
    return new LineItem(
        "Plan", "SAAS_18", dec(quantity), "NOS", dec(unitPrice), dec(discount), null, null);
  }

  private static TaxRate rate(String gstRate, String cessRate) {
    return new TaxRate(
        "SAAS_18", "998314", dec(gstRate), dec(cessRate), LocalDate.of(2017, 7, 1), null);
  }

  private static BigDecimal dec(String value) {
    return new BigDecimal(value);
  }
}
