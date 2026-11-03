package com.shashank.invoicekit.numbering.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks that a business unit's shard count fits its format. */
class NumberingRuleTest {

  private static final NumberFormat WITH_SHARD = NumberFormat.parse("SUB{shard}{fy}{seq:10}");
  private static final NumberFormat WITHOUT_SHARD = NumberFormat.parse("SUB{fy}{seq:11}");

  @Test
  void oneShardDoesNotNeedTheShardToken() {
    assertThat(new NumberingRule(WITHOUT_SHARD, 1).shards()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 26})
  void formatWithTheShardTokenAllowsUpToTwentySixShards(int shards) {
    assertThat(new NumberingRule(WITH_SHARD, shards).shards()).isEqualTo(shards);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 27})
  void shardCountMustBeOneToTwentySix(int shards) {
    assertThatThrownBy(() -> new NumberingRule(WITH_SHARD, shards))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Shard count must be 1 to 26, but was " + shards);
  }

  @Test
  void severalShardsNeedTheShardToken() {
    assertThatThrownBy(() -> new NumberingRule(WITHOUT_SHARD, 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Number format SUB{fy}{seq:11} needs {shard} to use 2 shards,"
                + " otherwise every shard would produce the same numbers");
  }

  @Test
  void formatIsRequired() {
    assertThatThrownBy(() -> new NumberingRule(null, 1))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("format is required");
  }
}
