package com.shashank.invoicekit.numbering.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import org.junit.jupiter.api.Test;

/** Checks that series failures name the series and say what to do. */
class SeriesExceptionsTest {

  private static final SeriesKey SERIES =
      new SeriesKey("ENT1", "subscriptions", new FinancialYear(2026), new Shard(0));

  @Test
  void exhaustedSeriesAsksForMoreShards() {
    SeriesExhaustedException exception = new SeriesExhaustedException(SERIES);

    assertThat(exception.series()).isEqualTo(SERIES);
    assertThat(exception)
        .hasMessage(
            "Series " + SERIES + " has reached its maximum sequence; configure more shards");
  }

  @Test
  void busySeriesKeepsTheCauseAndSaysRetryMayWork() {
    RuntimeException lockTimeout = new RuntimeException("lock timeout");

    SeriesBusyException exception = new SeriesBusyException(SERIES, lockTimeout);

    assertThat(exception.series()).isEqualTo(SERIES);
    assertThat(exception)
        .hasCause(lockTimeout)
        .hasMessage("Series " + SERIES + " was locked too long; retrying may succeed");
  }
}
