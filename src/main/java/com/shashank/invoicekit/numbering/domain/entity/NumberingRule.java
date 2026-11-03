package com.shashank.invoicekit.numbering.domain.entity;

import java.util.Objects;

/**
 * How one business unit numbers its invoices for one entity.
 *
 * @param format number template
 * @param shards how many parallel series to spread events across, 1 to 26
 */
public record NumberingRule(NumberFormat format, int shards) {

  public NumberingRule {
    Objects.requireNonNull(format, "format is required");
    Shard.requireValidCount(shards);
    if (shards > 1 && !format.usesShard()) {
      throw new IllegalArgumentException(
          "Number format "
              + format
              + " needs {shard} to use "
              + shards
              + " shards, otherwise every shard would produce the same numbers");
    }
  }
}
