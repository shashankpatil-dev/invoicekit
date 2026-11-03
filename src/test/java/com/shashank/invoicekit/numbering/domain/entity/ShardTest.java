package com.shashank.invoicekit.numbering.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks shard letters and how an event's shard is chosen. */
class ShardTest {

  @ParameterizedTest
  @CsvSource({"0, A", "1, B", "25, Z"})
  void codeIsTheLetterForTheIndex(int index, String code) {
    assertThat(new Shard(index).code()).isEqualTo(code);
    assertThat(new Shard(index)).hasToString(code);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 26})
  void indexMustBeZeroToTwentyFive(int index) {
    assertThatThrownBy(() -> new Shard(index))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Shard index must be 0 to 25, but was " + index);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 27})
  void shardCountMustBeOneToTwentySix(int shards) {
    assertThatThrownBy(() -> Shard.forEvent("event-1", shards))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Shard count must be 1 to 26, but was " + shards);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void eventIdIsRequired(String sourceEventId) {
    assertThatThrownBy(() -> Shard.forEvent(sourceEventId, 4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Source event ID is required");
  }

  @Test
  void sameEventAlwaysGetsTheSameShard() {
    // "abc".hashCode() is 96354, and 96354 mod 4 is 2.
    assertThat(Shard.forEvent("abc", 4)).isEqualTo(new Shard(2));
    assertThat(Shard.forEvent("abc", 4)).isEqualTo(Shard.forEvent("abc", 4));
  }

  @Test
  void oneShardAlwaysGivesShardA() {
    assertThat(Shard.forEvent("any-event", 1).code()).isEqualTo("A");
  }

  @Test
  void negativeHashCodeStillMapsIntoRange() {
    String sourceEventId = "polygenelubricants";
    assertThat(sourceEventId.hashCode()).isEqualTo(Integer.MIN_VALUE);

    // Integer.MIN_VALUE mod 3 is 1, where plain % would give -2.
    assertThat(Shard.forEvent(sourceEventId, 3).code()).isEqualTo("B");
  }

  @Test
  void eventsSpreadEvenlyAcrossShards() {
    int events = 10_000;
    Map<Shard, Long> counts =
        IntStream.range(0, events)
            .mapToObj(ShardTest::eventId)
            .map(sourceEventId -> Shard.forEvent(sourceEventId, 4))
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

    assertThat(counts).hasSize(4);
    assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween(2_000L, 3_000L));
  }

  private static String eventId(int i) {
    return UUID.nameUUIDFromBytes(("event-" + i).getBytes(StandardCharsets.UTF_8)).toString();
  }
}
