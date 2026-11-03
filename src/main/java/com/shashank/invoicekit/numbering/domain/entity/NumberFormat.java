package com.shashank.invoicekit.numbering.domain.entity;

import com.shashank.invoicekit.numbering.domain.entity.TemplatePart.Literal;
import com.shashank.invoicekit.numbering.domain.entity.TemplatePart.SequenceToken;
import com.shashank.invoicekit.numbering.domain.entity.TemplatePart.ShardToken;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A business unit's invoice number template, such as {@code SUB{shard}{fy}{seq:10}}.
 *
 * <p>A template mixes literal text with three tokens: {@code {shard}} (the shard letter), {@code
 * {fy}} (two-digit starting year of the financial year) and {@code {seq:N}} (the sequence,
 * zero-padded to N digits). Every part has a fixed width, so all numbers from one template have the
 * same length. GST Rule 46 allows at most 16 characters, using only letters, digits, {@code -} and
 * {@code /}. {@link NumberFormatParser} rejects any template that could break that.
 */
public final class NumberFormat {

  /** The longest invoice number GST Rule 46 allows. */
  public static final int MAX_LENGTH = 16;

  private final String template;
  private final List<TemplatePart> parts;
  private final long maxSequence;
  private final boolean usesShard;
  private final String leadingLiteral;

  private NumberFormat(String template, List<TemplatePart> parts) {
    this.template = template;
    this.parts = List.copyOf(parts);
    this.maxSequence = sequenceOf(parts).maxValue();
    this.usesShard = parts.stream().anyMatch(ShardToken.class::isInstance);
    this.leadingLiteral = parts.getFirst() instanceof Literal literal ? literal.text() : "";
  }

  /** Parses and validates a template, explaining the first problem found. */
  public static NumberFormat parse(String template) {
    return new NumberFormat(template, NumberFormatParser.parse(template));
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
    return parts.stream()
        .map(part -> part.render(shard, year, sequence))
        .collect(Collectors.joining());
  }

  /** The largest sequence the template can hold, such as 9,999,999,999 for {@code {seq:10}}. */
  public long maxSequence() {
    return maxSequence;
  }

  /** Whether numbers include the shard letter, which more than one shard needs. */
  public boolean usesShard() {
    return usesShard;
  }

  /**
   * The literal text before the first token, such as {@code SUB}. Two business units under one
   * entity must not share it, or their numbers could collide.
   */
  public String leadingLiteral() {
    return leadingLiteral;
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

  private static SequenceToken sequenceOf(List<TemplatePart> parts) {
    return parts.stream()
        .filter(SequenceToken.class::isInstance)
        .map(SequenceToken.class::cast)
        .findFirst()
        .orElseThrow();
  }
}
