package com.shashank.invoicekit.invoice.adapter.outbound.postgres;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.shashank.invoicekit.invoice.application.port.outbound.ReferenceData;
import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import com.shashank.invoicekit.invoice.domain.entity.Declaration;
import com.shashank.invoicekit.invoice.domain.entity.GstState;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Keeps reference data in memory for a set time, in front of another {@link ReferenceData}. Every
 * event needs the same few rows, and they change rarely, so each pod reads them about once per
 * time-to-live instead of once per event. An edit reaches intake within one time-to-live.
 *
 * <p>Missing rows are cached too, as empty results, so a flood of bad events can't flood the
 * database. The startup-check lookups run once, so they go straight to the source.
 */
public class CachedReferenceData implements ReferenceData {

  /** The one key all declarations are cached under, because they are always read together. */
  private enum AllRows {
    KEY
  }

  private record BusinessUnitEntityKey(String businessUnit, String entityCode) {}

  private record TaxRateKey(String taxCode, LocalDate date) {}

  private final ReferenceData source;
  private final LoadingCache<String, Optional<LegalEntity>> entities;
  private final LoadingCache<BusinessUnitEntityKey, Optional<BusinessUnitEntity>> access;
  private final LoadingCache<String, Optional<AdapterConfig>> configs;
  private final LoadingCache<String, Optional<GstState>> states;
  private final LoadingCache<TaxRateKey, Optional<TaxRate>> taxRates;
  private final LoadingCache<AllRows, List<Declaration>> declarations;

  public CachedReferenceData(ReferenceData source, Duration timeToLive) {
    this(source, timeToLive, Ticker.systemTicker());
  }

  /** Reads the time from {@code ticker}, so a test can move it forward. */
  CachedReferenceData(ReferenceData source, Duration timeToLive, Ticker ticker) {
    this.source = source;
    this.entities = cache(timeToLive, ticker, source::entity);
    this.access =
        cache(
            timeToLive,
            ticker,
            key -> source.businessUnitEntity(key.businessUnit(), key.entityCode()));
    this.configs = cache(timeToLive, ticker, source::activeConfig);
    this.states = cache(timeToLive, ticker, source::gstState);
    this.taxRates = cache(timeToLive, ticker, key -> source.taxRate(key.taxCode(), key.date()));
    this.declarations = cache(timeToLive, ticker, key -> source.declarations());
  }

  @Override
  public Optional<LegalEntity> entity(String entityCode) {
    return entities.get(entityCode);
  }

  @Override
  public Optional<BusinessUnitEntity> businessUnitEntity(String businessUnit, String entityCode) {
    return access.get(new BusinessUnitEntityKey(businessUnit, entityCode));
  }

  @Override
  public Optional<AdapterConfig> activeConfig(String businessUnit) {
    return configs.get(businessUnit);
  }

  @Override
  public Optional<GstState> gstState(String stateCode) {
    return states.get(stateCode);
  }

  @Override
  public Optional<TaxRate> taxRate(String taxCode, LocalDate date) {
    return taxRates.get(new TaxRateKey(taxCode, date));
  }

  @Override
  public List<Declaration> declarations() {
    return declarations.get(AllRows.KEY);
  }

  @Override
  public List<AdapterConfig> activeConfigs() {
    return source.activeConfigs();
  }

  @Override
  public List<BusinessUnitEntity> businessUnitEntities() {
    return source.businessUnitEntities();
  }

  private static <K, V> LoadingCache<K, V> cache(
      Duration timeToLive, Ticker ticker, CacheLoader<K, V> loader) {
    return Caffeine.newBuilder().expireAfterWrite(timeToLive).ticker(ticker).build(loader);
  }
}
