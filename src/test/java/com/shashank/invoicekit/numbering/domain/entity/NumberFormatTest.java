package com.shashank.invoicekit.numbering.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.numbering.domain.exception.InvalidNumberFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks template parsing, the Rule 46 limits and number output. */
class NumberFormatTest {

  private static final Shard SHARD_A = new Shard(0);
  private static final FinancialYear YEAR_2026 = new FinancialYear(2026);

  @Test
  void formatsTheChosenTemplate() {
    NumberFormat format = NumberFormat.parse("SUB{shard}{fy}{seq:10}");

    assertThat(format.format(SHARD_A, YEAR_2026, 142)).isEqualTo("SUBA260000000142");
  }

  @Test
  void tokensCanAppearInAnyOrderBetweenLiterals() {
    NumberFormat format = NumberFormat.parse("INV/{fy}/{shard}-{seq:4}");

    assertThat(format.format(new Shard(2), YEAR_2026, 7)).isEqualTo("INV/26/C-0007");
  }

  @ParameterizedTest
  @CsvSource({"1, 00001", "42, 00042", "99999, 99999"})
  void sequenceIsZeroPaddedToItsWidth(long sequence, String number) {
    NumberFormat format = NumberFormat.parse("{seq:5}");

    assertThat(format.format(SHARD_A, YEAR_2026, sequence)).isEqualTo(number);
  }

  @ParameterizedTest
  @CsvSource({"1, 9", "10, 9999999999", "16, 9999999999999999"})
  void maxSequenceFillsEveryDigit(int digits, long maxSequence) {
    assertThat(NumberFormat.parse("{seq:" + digits + "}").maxSequence()).isEqualTo(maxSequence);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 100})
  void formatRefusesASequenceThatDoesNotFit(long sequence) {
    NumberFormat format = NumberFormat.parse("{seq:2}");

    assertThatThrownBy(() -> format.format(SHARD_A, YEAR_2026, sequence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Sequence must be 1 to 99 for {seq:2}, but was " + sequence);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUB{shard}{fy}{seq:10}", "{seq:16}", "AB/CD-EF{fy}{seq:6}"})
  void allowsExactlySixteenCharacters(String template) {
    String number = NumberFormat.parse(template).format(SHARD_A, YEAR_2026, 1);

    assertThat(number).hasSize(NumberFormat.MAX_LENGTH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUBX{shard}{fy}{seq:10}", "{shard}{seq:16}", "ABCDEFGHIJ{fy}{seq:5}"})
  void rejectsSeventeenCharacters(String template) {
    assertThatThrownBy(() -> NumberFormat.parse(template))
        .isInstanceOf(InvalidNumberFormatException.class)
        .hasMessage(
            "Number format '"
                + template
                + "' is invalid: numbers would be 17 characters, over the limit of 16");
  }

  @ParameterizedTest
  @NullAndEmptySource
  void rejectsAnEmptyTemplate(String template) {
    assertThatThrownBy(() -> NumberFormat.parse(template))
        .isInstanceOf(InvalidNumberFormatException.class)
        .hasMessage("Number format is empty");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "SUB{seq:5           | it has an unclosed '{' or a stray '}'",
        "SUB}{seq:5}         | it has an unclosed '{' or a stray '}'",
        "{{seq:5}            | it has an unclosed '{' or a stray '}'",
        "\"SUB {seq:5}\"     | ' ' is not allowed; use only letters, digits, '-' and '/'",
        "SUB_{seq:5}         | '_' is not allowed; use only letters, digits, '-' and '/'",
        "SUBÉ{seq:5}         | 'É' is not allowed; use only letters, digits, '-' and '/'",
        "{year}{seq:5}       | unknown token {year}; use {shard}, {fy} or {seq:N}",
        "{SHARD}{seq:5}      | unknown token {SHARD}; use {shard}, {fy} or {seq:N}",
        "{shard:2}{seq:5}    | unknown token {shard:2}; use {shard}, {fy} or {seq:N}",
        "{fy:start}{seq:5}   | unknown token {fy:start}; use {shard}, {fy} or {seq:N}",
        "{}{seq:5}           | unknown token {}; use {shard}, {fy} or {seq:N}",
        "{seq}               | unknown token {seq}; use {shard}, {fy} or {seq:N}",
        "{seq:x}             | unknown token {seq:x}; use {shard}, {fy} or {seq:N}",
        "{seq:-1}            | unknown token {seq:-1}; use {shard}, {fy} or {seq:N}",
        "{seq:0}             | {seq:N} needs N from 1 to 16, but was 0",
        "{seq:17}            | {seq:N} needs N from 1 to 16, but was 17",
        "SUB{shard}{fy}      | it needs exactly one {seq:N}",
        "{seq:3}{seq:3}      | it needs exactly one {seq:N}",
        "{shard}{shard}{seq:3} | {shard} can appear only once",
        "{fy}{fy}{seq:3}     | {fy} can appear only once"
      })
  void rejectsAnInvalidTemplate(String template, String reason) {
    assertThatThrownBy(() -> NumberFormat.parse(template))
        .isInstanceOf(InvalidNumberFormatException.class)
        .hasMessage("Number format '" + template + "' is invalid: " + reason);
  }

  @ParameterizedTest
  @CsvSource({"SUB{shard}{seq:5}, true", "SUB{fy}{seq:5}, false"})
  void knowsWhetherItUsesTheShard(String template, boolean usesShard) {
    assertThat(NumberFormat.parse(template).usesShard()).isEqualTo(usesShard);
  }

  @ParameterizedTest
  @CsvSource({
    "SUB{shard}{fy}{seq:10}, SUB",
    "INV/{fy}/{seq:4}, INV/",
    "{shard}SUB{seq:5}, ''",
    "{seq:5}SUB, ''"
  })
  void leadingLiteralIsTheTextBeforeTheFirstToken(String template, String leadingLiteral) {
    assertThat(NumberFormat.parse(template).leadingLiteral()).isEqualTo(leadingLiteral);
  }

  @Test
  void formatsWithTheSameTemplateAreEqual() {
    NumberFormat format = NumberFormat.parse("SUB{shard}{seq:5}");

    assertThat(format)
        .isEqualTo(NumberFormat.parse("SUB{shard}{seq:5}"))
        .hasSameHashCodeAs(NumberFormat.parse("SUB{shard}{seq:5}"))
        .isNotEqualTo(NumberFormat.parse("SUB{shard}{seq:6}"))
        .isNotEqualTo("SUB{shard}{seq:5}")
        .hasToString("SUB{shard}{seq:5}");
  }
}
