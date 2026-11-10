package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.MONEY;

import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Writes an invoice total in words, such as "Rupees Seventeen Thousand One Hundred Ten Only".
 *
 * <p>INR uses Indian grouping (thousand, lakh, crore) and paise. Other currencies use international
 * grouping (thousand, million, billion) and cents.
 */
final class AmountInWords {

  String inWords(BigDecimal amount, String currencyCode) {
    CurrencyWords currency = CurrencyWords.of(currencyCode);
    BigDecimal rounded = MONEY.round(amount);
    long whole = rounded.longValue();
    int fraction = rounded.remainder(BigDecimal.ONE).movePointRight(MONEY.places()).intValue();
    String wholeWords = currency.majorUnit() + " " + currency.grouping().spell(whole);
    String fractionWords =
        fraction == 0
            ? ""
            : " and " + currency.grouping().spell(fraction) + " " + currency.minorUnit();
    return wholeWords + fractionWords + " Only";
  }

  /** The words a currency's amounts are written with. */
  private record CurrencyWords(String majorUnit, String minorUnit, Grouping grouping) {

    private static final CurrencyWords RUPEES =
        new CurrencyWords("Rupees", "Paise", Grouping.INDIAN);

    // shortcut: other currencies use Java's English name plus "s", and "Cents", which suits USD,
    // EUR, AUD, CAD and SGD; add a names table before invoicing in a currency such as GBP or JPY.
    static CurrencyWords of(String currencyCode) {
      if (InvoiceRequested.INR.equals(currencyCode)) {
        return RUPEES;
      }
      String name = Currency.getInstance(currencyCode).getDisplayName(Locale.ENGLISH);
      return new CurrencyWords(name + "s", "Cents", Grouping.INTERNATIONAL);
    }
  }

  /** A named power of ten, such as a lakh. */
  private record Scale(long value, String name) {}

  /** How a number is broken into named scales, largest first. */
  private enum Grouping {
    INDIAN(
        new Scale(10_000_000L, "Crore"),
        new Scale(100_000L, "Lakh"),
        new Scale(1_000L, "Thousand"),
        new Scale(100L, "Hundred")),
    INTERNATIONAL(
        new Scale(1_000_000_000_000L, "Trillion"),
        new Scale(1_000_000_000L, "Billion"),
        new Scale(1_000_000L, "Million"),
        new Scale(1_000L, "Thousand"),
        new Scale(100L, "Hundred"));

    private static final String[] ONES = {
      "",
      "One",
      "Two",
      "Three",
      "Four",
      "Five",
      "Six",
      "Seven",
      "Eight",
      "Nine",
      "Ten",
      "Eleven",
      "Twelve",
      "Thirteen",
      "Fourteen",
      "Fifteen",
      "Sixteen",
      "Seventeen",
      "Eighteen",
      "Nineteen"
    };

    private static final String[] TENS = {
      "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private final List<Scale> scales;

    Grouping(Scale... scales) {
      this.scales = List.of(scales);
    }

    String spell(long number) {
      return number == 0 ? "Zero" : spellPositive(number);
    }

    /**
     * Spells how many of the largest scale fit, then the rest. A count above the largest scale is
     * spelled the same way, as in "One Thousand Crore".
     */
    private String spellPositive(long number) {
      for (Scale scale : scales) {
        if (number >= scale.value()) {
          String head = spellPositive(number / scale.value()) + " " + scale.name();
          long rest = number % scale.value();
          return rest == 0 ? head : head + " " + spellPositive(rest);
        }
      }
      return belowHundred((int) number);
    }

    private static String belowHundred(int number) {
      if (number < ONES.length) {
        return ONES[number];
      }
      int ones = number % 10;
      return ones == 0 ? TENS[number / 10] : TENS[number / 10] + " " + ONES[ones];
    }
  }
}
