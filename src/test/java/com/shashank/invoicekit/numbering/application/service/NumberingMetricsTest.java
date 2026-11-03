package com.shashank.invoicekit.numbering.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** Checks the utilisation gauge and the one-time warning for a filling series. */
class NumberingMetricsTest {

  private static final SeriesKey SERIES =
      new SeriesKey("ENT1", "subscriptions", new FinancialYear(2026), new Shard(0));

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NumberingMetrics metrics = new NumberingMetrics(registry, 0.8);
  private final Logger logger = (Logger) LoggerFactory.getLogger(NumberingMetrics.class);
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logged.start();
    logger.addAppender(logged);
  }

  @AfterEach
  void stopCapturingLogs() {
    logger.detachAppender(logged);
  }

  @Test
  void warnsOnceWhenTheSeriesReachesTheWarningLevel() {
    metrics.recordAllocation(SERIES, 7, 9);
    assertThat(logged.list).isEmpty();

    metrics.recordAllocation(SERIES, 8, 9);
    metrics.recordAllocation(SERIES, 9, 9);

    assertThat(logged.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("ENT1", "subscriptions", "2026-27", "89%", "configure more shards");
            });
  }

  @Test
  void slowerParallelAllocationDoesNotLowerUtilisation() {
    metrics.recordAllocation(SERIES, 8, 9);
    metrics.recordAllocation(SERIES, 5, 9);

    assertThat(utilisation()).isCloseTo(8.0 / 9, within(1e-9));
  }

  @Test
  void exhaustedSeriesIsFull() {
    assertThatThrownBy(
            () ->
                metrics.measureAllocation(
                    SERIES,
                    () -> {
                      throw new SeriesExhaustedException(SERIES);
                    }))
        .isInstanceOf(SeriesExhaustedException.class);

    assertThat(utilisation()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, -0.5, 1.01, Double.NaN})
  void warningLevelMustBeAboveZeroAndAtMostOne(double level) {
    assertThatThrownBy(() -> new NumberingMetrics(registry, level))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Utilisation warning must be above 0 and at most 1, but was " + level);
  }

  private double utilisation() {
    return registry.get("numbering.series.utilisation").gauge().value();
  }
}
