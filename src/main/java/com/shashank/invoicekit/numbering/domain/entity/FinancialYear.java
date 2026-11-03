package com.shashank.invoicekit.numbering.domain.entity;

import java.time.LocalDate;
import java.time.Month;
import java.util.regex.Pattern;

/**
 * An Indian financial year, which runs from 1 April to 31 March. Every series starts again at 1
 * each financial year.
 *
 * @param startYear four-digit calendar year in which the financial year starts
 */
public record FinancialYear(int startYear) {

  private static final Pattern LABEL = Pattern.compile("\\d{4}-\\d{2}");

  public FinancialYear {
    if (startYear < 1000 || startYear > 9999) {
      throw new IllegalArgumentException(
          "Financial year must start in a four-digit year, but was " + startYear);
    }
  }

  /** Returns the financial year a date falls in, so 31 March 2027 is in 2026-27. */
  public static FinancialYear of(LocalDate date) {
    int year = date.getYear();
    return new FinancialYear(date.getMonthValue() >= Month.APRIL.getValue() ? year : year - 1);
  }

  /** Reads a stored label such as {@code 2026-27}, accepting only the canonical form. */
  public static FinancialYear parse(String label) {
    if (label == null || !LABEL.matcher(label).matches()) {
      throw new IllegalArgumentException(
          "Financial year must look like 2026-27, but was: " + label);
    }
    FinancialYear year = new FinancialYear(Integer.parseInt(label.substring(0, 4)));
    if (!year.label().equals(label)) {
      throw new IllegalArgumentException(
          "Financial year "
              + label
              + " must end in the year after it starts, as in "
              + year.label());
    }
    return year;
  }

  /** The canonical stored form, such as {@code 2026-27}. */
  public String label() {
    return "%d-%02d".formatted(startYear, (startYear + 1) % 100);
  }

  /** The starting year's last two digits, such as {@code 26}, used by the {@code {fy}} token. */
  public String shortCode() {
    return "%02d".formatted(startYear % 100);
  }

  @Override
  public String toString() {
    return label();
  }
}
