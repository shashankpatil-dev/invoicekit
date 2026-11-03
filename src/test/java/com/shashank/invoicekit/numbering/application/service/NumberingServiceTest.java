package com.shashank.invoicekit.numbering.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.shashank.invoicekit.numbering.application.port.outbound.SequenceCounter;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.numbering.domain.entity.NumberingRule;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Checks how the service picks a series, formats numbers and records what happened. */
class NumberingServiceTest {

  private static final String ENTITY = "ENT1";
  private static final String BUSINESS_UNIT = "subscriptions";
  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);
  private static final NumberingRule ONE_SHARD = rule("SUB{shard}{fy}{seq:10}", 1);
  private static final NumberingRule FOUR_SHARDS = rule("SUB{shard}{fy}{seq:10}", 4);

  /** Holds 9 numbers per series, so tests can fill one quickly. */
  private static final NumberingRule NINE_NUMBERS = rule("SUB{shard}{fy}{seq:1}", 1);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NumberingService service =
      new NumberingService(new InMemorySequenceCounter(), new NumberingMetrics(registry, 0.8));

  @Test
  void firstNumberOfTheSeriesIsOne() {
    InvoiceNumber number = service.assign(request("event-1", INVOICE_DATE, ONE_SHARD));

    assertThat(number.value()).isEqualTo("SUBA260000000001");
    assertThat(number.sequenceNo()).isEqualTo(1);
    assertThat(number.series())
        .isEqualTo(new SeriesKey(ENTITY, BUSINESS_UNIT, new FinancialYear(2026), new Shard(0)));
  }

  @Test
  void nextInvoiceTakesTheNextNumber() {
    service.assign(request("event-1", INVOICE_DATE, ONE_SHARD));

    assertThat(service.assign(request("event-2", INVOICE_DATE, ONE_SHARD)).value())
        .isEqualTo("SUBA260000000002");
  }

  @Test
  void marchBelongsToThePreviousFinancialYear() {
    InvoiceNumber number = service.assign(request("event-1", LocalDate.of(2026, 3, 31), ONE_SHARD));

    assertThat(number.series().financialYear()).isEqualTo(new FinancialYear(2025));
    assertThat(number.value()).isEqualTo("SUBA250000000001");
  }

  @Test
  void sameEventAlwaysLandsInTheSameShard() {
    InvoiceNumber first = service.assign(request("event-1", INVOICE_DATE, FOUR_SHARDS));
    InvoiceNumber second = service.assign(request("event-1", INVOICE_DATE, FOUR_SHARDS));

    assertThat(second.series()).isEqualTo(first.series());
  }

  @Test
  void eventsSpreadAcrossEveryShard() {
    Set<String> shardLetters =
        IntStream.range(0, 100)
            .mapToObj(i -> service.assign(request("event-" + i, INVOICE_DATE, FOUR_SHARDS)))
            .map(number -> number.value().substring(3, 4))
            .collect(Collectors.toSet());

    assertThat(shardLetters).containsExactlyInAnyOrder("A", "B", "C", "D");
    assertThat(registry.find("numbering.series.utilisation").gauges()).hasSize(4);
  }

  @Test
  void asksTheCounterForTheRequestSeriesUpToTheFormatMaximum() {
    AtomicReference<SeriesKey> askedSeries = new AtomicReference<>();
    AtomicLong askedMaximum = new AtomicLong();
    SequenceCounter recording =
        (series, maxSequence) -> {
          askedSeries.set(series);
          askedMaximum.set(maxSequence);
          return 1;
        };
    NumberingRequest request = request("event-1", INVOICE_DATE, FOUR_SHARDS);

    new NumberingService(recording, new NumberingMetrics(registry, 0.8)).assign(request);

    assertThat(askedSeries.get()).isEqualTo(request.series());
    assertThat(askedMaximum.get()).isEqualTo(9_999_999_999L);
  }

  @Test
  void exhaustedSeriesFailsAndIsCounted() {
    assignNumbers(9, NINE_NUMBERS);

    assertThatThrownBy(() -> service.assign(request("event-10", INVOICE_DATE, NINE_NUMBERS)))
        .isInstanceOf(SeriesExhaustedException.class);
    assertThat(
            registry
                .get("numbering.exhausted")
                .tags("businessUnit", BUSINESS_UNIT, "entity", ENTITY)
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void busySeriesFailsUnchangedWithoutCountingAsExhausted() {
    NumberingRequest request = request("event-1", INVOICE_DATE, ONE_SHARD);
    SeriesBusyException busy =
        new SeriesBusyException(request.series(), new IllegalStateException("lock timeout"));
    SequenceCounter locked =
        (series, maxSequence) -> {
          throw busy;
        };

    assertThatThrownBy(
            () -> new NumberingService(locked, new NumberingMetrics(registry, 0.8)).assign(request))
        .isSameAs(busy);
    assertThat(registry.get("numbering.exhausted").counter().count()).isZero();
    assertThat(registry.get("numbering.allocation").timer().count()).isEqualTo(1);
  }

  @Test
  void countsAllocationsPerShardAndTracksHowFullTheSeriesIs() {
    assignNumbers(3, NINE_NUMBERS);

    assertThat(
            registry
                .get("numbering.allocations")
                .tags("businessUnit", BUSINESS_UNIT, "entity", ENTITY, "shard", "A")
                .counter()
                .count())
        .isEqualTo(3);
    assertThat(
            registry
                .get("numbering.series.utilisation")
                .tags(
                    "businessUnit",
                    BUSINESS_UNIT,
                    "entity",
                    ENTITY,
                    "financialYear",
                    "2026-27",
                    "shard",
                    "A")
                .gauge()
                .value())
        .isCloseTo(3.0 / 9, within(1e-9));
    assertThat(registry.get("numbering.allocation").timer().count()).isEqualTo(3);
  }

  @Test
  void assignRunsOnlyInsideTheCallerTransaction() throws NoSuchMethodException {
    Transactional transactional =
        NumberingService.class
            .getMethod("assign", NumberingRequest.class)
            .getAnnotation(Transactional.class);

    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(Modifier.isFinal(NumberingService.class.getModifiers()))
        .as("Spring must be able to proxy the service")
        .isFalse();
  }

  private void assignNumbers(int count, NumberingRule rule) {
    IntStream.rangeClosed(1, count)
        .forEach(i -> service.assign(request("event-" + i, INVOICE_DATE, rule)));
  }

  private static NumberingRequest request(
      String sourceEventId, LocalDate invoiceDate, NumberingRule rule) {
    return new NumberingRequest(ENTITY, BUSINESS_UNIT, sourceEventId, invoiceDate, rule);
  }

  private static NumberingRule rule(String format, int shards) {
    return new NumberingRule(NumberFormat.parse(format), shards);
  }

  /** Counts in memory and refuses numbers past the maximum, as the real counter does. */
  private static final class InMemorySequenceCounter implements SequenceCounter {

    private final Map<SeriesKey, Long> lastNumbers = new HashMap<>();

    @Override
    public long next(SeriesKey series, long maxSequence) {
      long next = lastNumbers.getOrDefault(series, 0L) + 1;
      if (next > maxSequence) {
        throw new SeriesExhaustedException(series);
      }
      lastNumbers.put(series, next);
      return next;
    }
  }
}
