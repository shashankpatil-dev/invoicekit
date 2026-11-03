package com.shashank.invoicekit.numbering.domain.entity;

import com.shashank.invoicekit.numbering.domain.exception.InvalidNumberFormatException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A business unit's invoice number template, such as {@code SUB{shard}{fy}{seq:10}}.
 *
 * <p>A template mixes literal text with three tokens: {@code {shard}} (the shard letter), {@code
 * {fy}} (two-digit starting year of the financial year) and {@code {seq:N}} (the sequence,
 * zero-padded to N digits). Every part has a fixed width, so all numbers from one template have the
 * same length. GST Rule 46 allows at most 16 characters, using only letters, digits, {@code -} and
 * {@code /}, and parsing rejects any template that could break that.
 */
public final class NumberFormat {

  /** The longest invoice number GST Rule 46 allows. */
  public static final int MAX_LENGTH = 16;

  /** Matches one token, such as {@code {seq:10}}, or a run of allowed literal characters. */
  private static final Pattern PART = Pattern.compile("\\{([^{}]*)}|[A-Za-z0-9/-]+");

  private static final Pattern SEQUENCE = Pattern.compile("seq:(\\d{1,9})");

  private final String template;
  private final List<Part> parts;
  private final long maxSequence;

  private NumberFormat(String template, List<Part> parts, int sequenceDigits) {
    this.template = template;
    this.parts = List.copyOf(parts);
    this.maxSequence = Long.parseLong("9".repeat(sequenceDigits));
  }

  /** Parses and validates a template, explaining the first problem found. */
  public static NumberFormat parse(String template) {
    if (template == null || template.isEmpty()) {
      throw new InvalidNumberFormatException("Number format is empty");
    }
    List<Part> parts = new ArrayList<>();
    Matcher matcher = PART.matcher(template);
    int position = 0;
    while (position < template.length()) {
      if (!matcher.region(position, template.length()).lookingAt()) {
        throw invalid(template, unexpected(template.charAt(position)));
      }
      String token = matcher.group(1);
      parts.add(token == null ? new Literal(matcher.group()) : token(template, token));
      position = matcher.end();
    }

    List<SequenceToken> sequences = partsOf(parts, SequenceToken.class);
    if (sequences.size() != 1) {
      throw invalid(template, "it needs exactly one {seq:N}");
    }
    if (partsOf(parts, ShardToken.class).size() > 1) {
      throw invalid(template, "{shard} can appear only once");
    }
    if (partsOf(parts, YearToken.class).size() > 1) {
      throw invalid(template, "{fy} can appear only once");
    }
    int length = parts.stream().mapToInt(NumberFormat::length).sum();
    if (length > MAX_LENGTH) {
      throw invalid(
          template, "numbers would be " + length + " characters, over the limit of " + MAX_LENGTH);
    }
    return new NumberFormat(template, parts, sequences.getFirst().digits());
  }

  /**
   * Builds an invoice number. A sequence that doesn't fit in {@code {seq:N}} is refused, because
   * cutting it to fit would repeat earlier numbers.
   */
  public String format(Shard shard, FinancialYear year, long sequence) {
    if (sequence < 1 || sequence > maxSequence) {
      throw new IllegalArgumentException(
          "Sequence must be 1 to " + maxSequence + " for " + template + ", but was " + sequence);
    }
    StringBuilder number = new StringBuilder();
    for (Part part : parts) {
      number.append(
          switch (part) {
            case Literal literal -> literal.text();
            case ShardToken token -> shard.code();
            case YearToken token -> year.shortCode();
            case SequenceToken token -> String.format("%0" + token.digits() + "d", sequence);
          });
    }
    return number.toString();
  }

  /** The largest sequence the template can hold, such as 9,999,999,999 for {@code {seq:10}}. */
  public long maxSequence() {
    return maxSequence;
  }

  /** Whether numbers include the shard letter, which more than one shard needs. */
  public boolean usesShard() {
    return !partsOf(parts, ShardToken.class).isEmpty();
  }

  /**
   * The literal text before the first token, such as {@code SUB}. Two business units under one
   * entity must not share it, or their numbers could collide.
   */
  public String leadingLiteral() {
    return parts.getFirst() instanceof Literal literal ? literal.text() : "";
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof NumberFormat format && template.equals(format.template);
  }

  @Override
  public int hashCode() {
    return template.hashCode();
  }

  @Override
  public String toString() {
    return template;
  }

  private static Part token(String template, String name) {
    return switch (name) {
      case "shard" -> new ShardToken();
      case "fy" -> new YearToken();
      default -> sequenceToken(template, name);
    };
  }

  private static SequenceToken sequenceToken(String template, String name) {
    Matcher sequence = SEQUENCE.matcher(name);
    if (!sequence.matches()) {
      throw invalid(template, "unknown token {" + name + "}; use {shard}, {fy} or {seq:N}");
    }
    int digits = Integer.parseInt(sequence.group(1));
    if (digits < 1 || digits > MAX_LENGTH) {
      throw invalid(template, "{seq:N} needs N from 1 to 16, but was " + digits);
    }
    return new SequenceToken(digits);
  }

  private static String unexpected(char character) {
    if (character == '{' || character == '}') {
      return "it has an unclosed '{' or a stray '}'";
    }
    return "'" + character + "' is not allowed; use only letters, digits, '-' and '/'";
  }

  private static <T extends Part> List<T> partsOf(List<Part> parts, Class<T> type) {
    return parts.stream().filter(type::isInstance).map(type::cast).toList();
  }

  /** How many characters a part adds to every number. */
  private static int length(Part part) {
    return switch (part) {
      case Literal literal -> literal.text().length();
      case ShardToken token -> 1;
      case YearToken token -> 2;
      case SequenceToken token -> token.digits();
    };
  }

  private static InvalidNumberFormatException invalid(String template, String reason) {
    return new InvalidNumberFormatException(
        "Number format '" + template + "' is invalid: " + reason);
  }

  /** One piece of a parsed template: literal text or a token. */
  private sealed interface Part {}

  private record Literal(String text) implements Part {}

  private record ShardToken() implements Part {}

  private record YearToken() implements Part {}

  private record SequenceToken(int digits) implements Part {}
}
