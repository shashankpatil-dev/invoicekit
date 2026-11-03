package com.shashank.invoicekit.numbering.domain.entity;

/**
 * One of up to 26 parallel series for the same entity, business unit and financial year. Busy
 * business units split their events across shards so each counter takes part of the load.
 *
 * @param index zero-based shard index, 0 to 25
 */
public record Shard(int index) {

  /** One letter per shard, A to Z. */
  public static final int MAX_SHARDS = 26;

  public Shard {
    if (index < 0 || index >= MAX_SHARDS) {
      throw new IllegalArgumentException("Shard index must be 0 to 25, but was " + index);
    }
  }

  /**
   * Picks the shard for an event. The same event always gets the same shard, and events spread
   * evenly across shards. {@code String.hashCode} is fixed by the Java language, so the choice is
   * the same on every JVM.
   */
  public static Shard forEvent(String sourceEventId, int shards) {
    requireValidCount(shards);
    if (sourceEventId == null || sourceEventId.isBlank()) {
      throw new IllegalArgumentException("Source event ID is required to choose a shard");
    }
    return new Shard(Math.floorMod(sourceEventId.hashCode(), shards));
  }

  /** Checks a configured shard count, which must give every shard its own letter. */
  static void requireValidCount(int shards) {
    if (shards < 1 || shards > MAX_SHARDS) {
      throw new IllegalArgumentException("Shard count must be 1 to 26, but was " + shards);
    }
  }

  /** The shard's letter, {@code A} for index 0 up to {@code Z} for index 25. */
  public String code() {
    return Character.toString('A' + index);
  }

  @Override
  public String toString() {
    return code();
  }
}
