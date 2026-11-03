package com.shashank.invoicekit.numbering.flow;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.numbering.domain.entity.NumberingRule;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import java.util.List;
import java.util.stream.LongStream;

/**
 * The business units the numbering flow tests sell under, and how each numbers invoices.
 *
 * <p>All sell under one entity, so each needs its own prefix, or their numbers could collide. The
 * only exception is {@link #ONE_SHARD_RESHARDED_TO_FOUR}, which is the same business unit as {@link
 * #ONE_SHARD} after its rule changed.
 */
enum TestBusinessUnit {
  ONE_SHARD("numbering-test", "SUB", 1, 10),
  FOUR_SHARDS("numbering-test-sharded", "SHD", 4, 10),

  /** {@link #ONE_SHARD} after an operator raised its shards to four: same code, new rule. */
  ONE_SHARD_RESHARDED_TO_FOUR("numbering-test", "SUB", 4, 10),

  /** Two-digit sequences, so each of its series runs out after 99 numbers. */
  TWO_DIGIT_SEQUENCE("numbering-test-two-digit", "TST", 1, 2);

  /** The fake legal entity every business unit invoices under. */
  static final String ENTITY_CODE = "TEST_NUMBERING";

  private static final Shard FIRST_SHARD = new Shard(0);

  private final String code;
  private final String prefix;
  private final NumberingRule rule;

  TestBusinessUnit(String code, String prefix, int shards, int sequenceDigits) {
    this.code = code;
    this.prefix = prefix;
    this.rule =
        new NumberingRule(
            NumberFormat.parse("%s{shard}{fy}{seq:%d}".formatted(prefix, sequenceDigits)), shards);
  }

  /** The name stored in the {@code bu} columns, such as {@code numbering-test}. */
  String code() {
    return code;
  }

  /** The text every number starts with, just before the shard letter. */
  String prefix() {
    return prefix;
  }

  NumberingRule rule() {
    return rule;
  }

  /**
   * The first {@code count} numbers of shard A in a financial year, in order, such as {@code
   * SUBA260000000001} onwards. For a one-shard business unit these are all the numbers it issues.
   */
  List<String> firstNumbers(FinancialYear financialYear, int count) {
    return LongStream.rangeClosed(1, count)
        .mapToObj(sequence -> rule.format().format(FIRST_SHARD, financialYear, sequence))
        .toList();
  }
}
