package com.shashank.invoicekit.numbering.domain.entity;

/**
 * One piece of a number template: fixed text, or a token filled in for each invoice.
 *
 * <p>Each part knows its own width and how to render itself, so adding a token means adding one
 * type here, with no other code to change.
 */
sealed interface TemplatePart {

  /** How many characters this part adds to every number. */
  int width();

  /** This part's text in one invoice number. */
  String render(Shard shard, FinancialYear year, long sequence);

  /** How the part is written in a template, used in error messages. */
  String label();

  /** Only literal text may appear more than once in a template. */
  default boolean repeatable() {
    return false;
  }

  /** Fixed text, such as {@code SUB}. */
  record Literal(String text) implements TemplatePart {

    @Override
    public int width() {
      return text.length();
    }

    @Override
    public String render(Shard shard, FinancialYear year, long sequence) {
      return text;
    }

    @Override
    public String label() {
      return text;
    }

    @Override
    public boolean repeatable() {
      return true;
    }
  }

  /** The {@code {shard}} token: the shard letter. */
  record ShardToken() implements TemplatePart {

    @Override
    public int width() {
      return 1;
    }

    @Override
    public String render(Shard shard, FinancialYear year, long sequence) {
      return shard.code();
    }

    @Override
    public String label() {
      return "{shard}";
    }
  }

  /** The {@code {fy}} token: the two-digit starting year of the financial year. */
  record YearToken() implements TemplatePart {

    @Override
    public int width() {
      return 2;
    }

    @Override
    public String render(Shard shard, FinancialYear year, long sequence) {
      return year.shortCode();
    }

    @Override
    public String label() {
      return "{fy}";
    }
  }

  /** The {@code {seq:N}} token: the sequence, zero-padded to a fixed number of digits. */
  record SequenceToken(int digits) implements TemplatePart {

    @Override
    public int width() {
      return digits;
    }

    @Override
    public String render(Shard shard, FinancialYear year, long sequence) {
      return String.format("%0" + digits + "d", sequence);
    }

    @Override
    public String label() {
      return "{seq:N}";
    }

    /** The largest sequence that fits, such as 999 for three digits. */
    long maxValue() {
      return Long.parseLong("9".repeat(digits));
    }
  }
}
