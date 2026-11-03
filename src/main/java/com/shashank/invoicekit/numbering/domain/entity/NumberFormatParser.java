package com.shashank.invoicekit.numbering.domain.entity;

import com.shashank.invoicekit.numbering.domain.entity.TemplatePart.Literal;
import com.shashank.invoicekit.numbering.domain.entity.TemplatePart.SequenceToken;
import com.shashank.invoicekit.numbering.domain.entity.TemplatePart.ShardToken;
import com.shashank.invoicekit.numbering.domain.entity.TemplatePart.YearToken;
import com.shashank.invoicekit.numbering.domain.exception.InvalidNumberFormatException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a number template into its parts, rejecting anything that could produce a number GST Rule
 * 46 doesn't allow. Each problem is reported where it is first found, with the reason.
 */
final class NumberFormatParser {

  /** Matches one token, such as {@code {seq:10}}, or a run of allowed literal characters. */
  private static final Pattern PART = Pattern.compile("\\{([^{}]*)}|[A-Za-z0-9/-]+");

  private static final Pattern SEQUENCE = Pattern.compile("seq:(\\d{1,9})");

  private final String template;

  private NumberFormatParser(String template) {
    this.template = template;
  }

  /** Returns the template's parts, or throws {@link InvalidNumberFormatException}. */
  static List<TemplatePart> parse(String template) {
    if (template == null || template.isEmpty()) {
      throw new InvalidNumberFormatException("Number format is empty");
    }
    NumberFormatParser parser = new NumberFormatParser(template);
    List<TemplatePart> parts = parser.readParts();
    parser.requireSequence(parts);
    parser.requireFitsLimit(parts);
    return parts;
  }

  /** Splits the template into parts, rejecting unknown text and repeated tokens as it reads. */
  private List<TemplatePart> readParts() {
    List<TemplatePart> parts = new ArrayList<>();
    Set<Class<? extends TemplatePart>> tokensSeen = new HashSet<>();
    Matcher matcher = PART.matcher(template);
    for (int position = 0; position < template.length(); position = matcher.end()) {
      if (!matcher.region(position, template.length()).lookingAt()) {
        throw invalid(unexpected(template.charAt(position)));
      }
      TemplatePart part = toPart(matcher);
      if (!part.repeatable() && !tokensSeen.add(part.getClass())) {
        throw invalid(part.label() + " can appear only once");
      }
      parts.add(part);
    }
    return parts;
  }

  private TemplatePart toPart(Matcher matcher) {
    String token = matcher.group(1);
    if (token == null) {
      return new Literal(matcher.group());
    }
    return switch (token) {
      case "shard" -> new ShardToken();
      case "fy" -> new YearToken();
      default -> sequenceToken(token);
    };
  }

  private SequenceToken sequenceToken(String token) {
    Matcher sequence = SEQUENCE.matcher(token);
    if (!sequence.matches()) {
      throw invalid("unknown token {" + token + "}; use {shard}, {fy} or {seq:N}");
    }
    int digits = Integer.parseInt(sequence.group(1));
    if (digits < 1 || digits > NumberFormat.MAX_LENGTH) {
      throw invalid("{seq:N} needs N from 1 to 16, but was " + digits);
    }
    return new SequenceToken(digits);
  }

  private void requireSequence(List<TemplatePart> parts) {
    if (parts.stream().noneMatch(SequenceToken.class::isInstance)) {
      throw invalid("it needs a {seq:N}");
    }
  }

  private void requireFitsLimit(List<TemplatePart> parts) {
    int length = parts.stream().mapToInt(TemplatePart::width).sum();
    if (length > NumberFormat.MAX_LENGTH) {
      throw invalid(
          "numbers would be "
              + length
              + " characters, over the limit of "
              + NumberFormat.MAX_LENGTH);
    }
  }

  private static String unexpected(char character) {
    if (character == '{' || character == '}') {
      return "it has an unclosed '{' or a stray '}'";
    }
    return "'" + character + "' is not allowed; use only letters, digits, '-' and '/'";
  }

  private InvalidNumberFormatException invalid(String reason) {
    return new InvalidNumberFormatException(
        "Number format '" + template + "' is invalid: " + reason);
  }
}
