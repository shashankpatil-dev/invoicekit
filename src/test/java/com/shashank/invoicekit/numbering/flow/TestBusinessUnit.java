package com.shashank.invoicekit.numbering.flow;

import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.numbering.domain.entity.NumberingRule;

/**
 * The business units in the temporary test series (db/testseries), and how each numbers invoices.
 *
 * <p>Both sell under one entity, so each needs its own prefix, or their numbers could collide.
 */
enum TestBusinessUnit {
  ONE_SHARD("numbering-test", "SUB", 1),
  FOUR_SHARDS("numbering-test-sharded", "SHD", 4);

  /** The fake legal entity both business units invoice under. */
  static final String ENTITY_CODE = "TEST_NUMBERING";

  private final String code;
  private final String prefix;
  private final NumberingRule rule;

  TestBusinessUnit(String code, String prefix, int shards) {
    this.code = code;
    this.prefix = prefix;
    this.rule = new NumberingRule(NumberFormat.parse(prefix + "{shard}{fy}{seq:10}"), shards);
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
}
