package com.shashank.invoicekit.numbering.application.service;

import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records how numbering behaves: how long the counter takes, how numbers spread across shards, and
 * how full each series is.
 *
 * <p>A series that fills up can't issue more numbers, so the first time one reaches the warning
 * level a warning is logged, giving time to configure more shards.
 */
public class NumberingMetrics {

  private static final Logger log = LoggerFactory.getLogger(NumberingMetrics.class);

  private final MeterRegistry registry;
  private final double utilisationWarning;
  private final Map<SeriesKey, SeriesMeters> seriesMeters = new ConcurrentHashMap<>();

  /**
   * Creates the metrics with a configured warning level.
   *
   * @param registry where meters are registered
   * @param utilisationWarning share of a series, above 0 and up to 1, at which to log a warning
   */
  public NumberingMetrics(MeterRegistry registry, double utilisationWarning) {
    if (!(utilisationWarning > 0 && utilisationWarning <= 1)) {
      throw new IllegalArgumentException(
          "Utilisation warning must be above 0 and at most 1, but was " + utilisationWarning);
    }
    this.registry = registry;
    this.utilisationWarning = utilisationWarning;
  }

  /** Times one call to the series' counter, and counts the call if the series is exhausted. */
  public long measureAllocation(SeriesKey series, LongSupplier allocation) {
    return metersFor(series).time(allocation);
  }

  /** Counts a number taken from the series, warning once when the series is nearly full. */
  public void recordAllocation(SeriesKey series, long sequence, long maxSequence) {
    SeriesMeters meters = metersFor(series);
    double utilisation = meters.countAllocation(sequence, maxSequence);
    if (utilisation >= utilisationWarning && meters.markWarned()) {
      log.warn(
          "Series {} has used {}% of its numbers; configure more shards before it runs out",
          series, Math.round(utilisation * 100));
    }
  }

  private SeriesMeters metersFor(SeriesKey series) {
    return seriesMeters.computeIfAbsent(series, key -> new SeriesMeters(registry, key));
  }

  /** One series' meters, registered once when the series is first used. */
  private static final class SeriesMeters {

    private final Timer allocationTime;
    private final Counter allocations;
    private final Counter exhausted;

    /** Highest share of the series used so far, so a slower parallel call can't lower it. */
    private final DoubleAccumulator utilisation = new DoubleAccumulator(Math::max, 0);

    private final AtomicBoolean warned = new AtomicBoolean();

    SeriesMeters(MeterRegistry registry, SeriesKey series) {
      Tags owner = Tags.of("businessUnit", series.businessUnit(), "entity", series.entityCode());
      Tags shard = owner.and("shard", series.shard().code());
      allocationTime = registry.timer("numbering.allocation", owner);
      allocations = registry.counter("numbering.allocations", shard);
      exhausted = registry.counter("numbering.exhausted", owner);
      Gauge.builder("numbering.series.utilisation", utilisation, DoubleAccumulator::get)
          .tags(shard.and("financialYear", series.financialYear().label()))
          .register(registry);
    }

    /** Times a counter call. An exhausted series is full, so its utilisation becomes 1. */
    long time(LongSupplier allocation) {
      try {
        return allocationTime.record(allocation);
      } catch (SeriesExhaustedException full) {
        exhausted.increment();
        utilisation.accumulate(1);
        throw full;
      }
    }

    /** Counts a number taken and returns how full the series now is. */
    double countAllocation(long sequence, long maxSequence) {
      allocations.increment();
      utilisation.accumulate((double) sequence / maxSequence);
      return utilisation.get();
    }

    /** Marks the series as warned, returning false if it already was. */
    boolean markWarned() {
      return warned.compareAndSet(false, true);
    }
  }
}
