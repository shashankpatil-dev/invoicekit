package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.invoice.application.port.outbound.FakeReferenceData;
import com.shashank.invoicekit.invoice.application.port.outbound.ReferenceData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Checks the cache answers repeats itself and reads the source again once its time is up. */
class CachedReferenceDataTest {

  private static final Duration TIME_TO_LIVE = Duration.ofMinutes(5);
  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 1);

  private final FakeReferenceData source = FakeReferenceData.supersetExample();

  /** The cache's clock in nanoseconds, moved by hand. */
  private final AtomicLong nanos = new AtomicLong();

  private final CachedReferenceData cache =
      new CachedReferenceData(source, TIME_TO_LIVE, nanos::get);

  /** Every lookup an event makes, each of which the cache must answer. */
  static Stream<Named<Consumer<ReferenceData>>> eventLookups() {
    return Stream.of(
        Named.of("entity", data -> data.entity("ACME_MH")),
        Named.of(
            "business unit entity", data -> data.businessUnitEntity("subscriptions", "ACME_MH")),
        Named.of("active config", data -> data.activeConfig("subscriptions")),
        Named.of("GST state", data -> data.gstState("27")),
        Named.of("tax rate", data -> data.taxRate("SAAS_18", INVOICE_DATE)),
        Named.of("declarations", ReferenceData::declarations),
        Named.of("missing entity", data -> data.entity("NO_SUCH_ENTITY")));
  }

  @ParameterizedTest
  @MethodSource("eventLookups")
  void answersARepeatFromTheCache(Consumer<ReferenceData> lookup) {
    lookup.accept(cache);
    lookup.accept(cache);

    assertThat(source.lookups()).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("eventLookups")
  void readsTheSourceAgainOnceTheTimeToLiveHasPassed(Consumer<ReferenceData> lookup) {
    lookup.accept(cache);
    nanos.addAndGet(TIME_TO_LIVE.plusNanos(1).toNanos());
    lookup.accept(cache);

    assertThat(source.lookups()).isEqualTo(2);
  }

  @Test
  void returnsWhatTheSourceReturns() {
    assertThat(cache.activeConfig("subscriptions")).isEqualTo(source.activeConfig("subscriptions"));
    assertThat(cache.taxRate("SAAS_18", INVOICE_DATE))
        .isEqualTo(source.taxRate("SAAS_18", INVOICE_DATE));
  }

  @Test
  void keepsTheTaxRateForEachDateApart() {
    cache.taxRate("SAAS_18", INVOICE_DATE);

    assertThat(cache.taxRate("SAAS_18", LocalDate.of(2017, 6, 30))).isEmpty();
  }

  @Test
  void startupLookupsAlwaysReadTheSource() {
    cache.activeConfigs();
    cache.activeConfigs();
    cache.businessUnitEntities();

    assertThat(source.lookups()).isEqualTo(3);
  }
}
