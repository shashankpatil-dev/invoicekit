package com.shashank.invoicekit.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks amounts in words: Indian grouping for rupees, international for other currencies. */
class AmountInWordsTest {

  private final AmountInWords amountInWords = new AmountInWords();

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "17110.00     | Rupees Seventeen Thousand One Hundred Ten Only",
        "0.50         | Rupees Zero and Fifty Paise Only",
        "0            | Rupees Zero Only",
        "1            | Rupees One Only",
        "19           | Rupees Nineteen Only",
        "20           | Rupees Twenty Only",
        "99.99        | Rupees Ninety Nine and Ninety Nine Paise Only",
        "100000       | Rupees One Lakh Only",
        "10000000     | Rupees One Crore Only",
        "1234567.89   | Rupees Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven and Eighty"
            + " Nine Paise Only",
        "10000000000  | Rupees One Thousand Crore Only",
        "1.005        | Rupees One and One Paise Only"
      })
  void writesRupeesWithLakhsAndCrores(String amount, String words) {
    assertThat(amountInWords.inWords(new BigDecimal(amount), "INR")).isEqualTo(words);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "USD | 1000.00       | US Dollars One Thousand Only",
        "USD | 1234567.05    | US Dollars One Million Two Hundred Thirty Four Thousand Five Hundred"
            + " Sixty Seven and Five Cents Only",
        "USD | 100000        | US Dollars One Hundred Thousand Only",
        "USD | 2000000000000 | US Dollars Two Trillion Only",
        "EUR | 19.99         | Euros Nineteen and Ninety Nine Cents Only"
      })
  void writesOtherCurrenciesWithMillions(String currency, String amount, String words) {
    assertThat(amountInWords.inWords(new BigDecimal(amount), currency)).isEqualTo(words);
  }
}
