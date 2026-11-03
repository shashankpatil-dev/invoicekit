package com.shashank.invoicekit.numbering.domain.exception;

import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;

/**
 * A series has issued its largest number, so retrying won't help. The business unit needs more
 * shards, which start new series.
 */
public class SeriesExhaustedException extends RuntimeException {

  private final SeriesKey series;

  public SeriesExhaustedException(SeriesKey series) {
    super("Series " + series + " has reached its maximum sequence; configure more shards");
    this.series = series;
  }

  public SeriesKey series() {
    return series;
  }
}
