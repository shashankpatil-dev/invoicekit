package com.shashank.invoicekit.numbering.domain.exception;

import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;

/**
 * Another transaction held a series' counter for too long, so no number was taken. Retrying may
 * succeed.
 */
public class SeriesBusyException extends RuntimeException {

  private final SeriesKey series;

  public SeriesBusyException(SeriesKey series, Throwable cause) {
    super("Series " + series + " was locked too long; retrying may succeed", cause);
    this.series = series;
  }

  public SeriesKey series() {
    return series;
  }
}
