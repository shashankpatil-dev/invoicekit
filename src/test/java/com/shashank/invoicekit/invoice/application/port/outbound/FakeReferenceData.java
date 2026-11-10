package com.shashank.invoicekit.invoice.application.port.outbound;

import com.shashank.invoicekit.invoice.domain.IntakeFixtures;
import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import com.shashank.invoicekit.invoice.domain.entity.Declaration;
import com.shashank.invoicekit.invoice.domain.entity.GstState;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference data held in memory, holding the Invoice Superset example's rows. It counts every
 * lookup, so a test can tell whether a cache in front of it was used.
 */
public final class FakeReferenceData implements ReferenceData {

  private final Map<String, LegalEntity> entities = new HashMap<>();
  private final List<BusinessUnitEntity> businessUnitEntities = new ArrayList<>();
  private final Map<String, AdapterConfig> configs = new HashMap<>();
  private final Map<String, GstState> states = new HashMap<>();
  private final List<TaxRate> taxRates = new ArrayList<>();
  private final List<Declaration> declarations = new ArrayList<>();
  private final AtomicInteger lookups = new AtomicInteger();

  private FakeReferenceData() {}

  /**
   * The example's data: entity {@code ACME_MH}, which {@code subscriptions} may use on one shard,
   * Maharashtra and Karnataka, {@code SAAS_18} and the seeded declarations.
   */
  public static FakeReferenceData supersetExample() {
    FakeReferenceData data = new FakeReferenceData();
    LegalEntity supplier = IntakeFixtures.acmeMaharashtra();
    AdapterConfig config = IntakeFixtures.subscriptionsConfig();
    data.entities.put(supplier.entityCode(), supplier);
    data.configs.put(config.businessUnit(), config);
    data.businessUnitEntities.add(
        new BusinessUnitEntity(config.businessUnit(), supplier.entityCode(), 1));
    data.states.put(IntakeFixtures.MAHARASHTRA.code(), IntakeFixtures.MAHARASHTRA);
    data.states.put(IntakeFixtures.KARNATAKA.code(), IntakeFixtures.KARNATAKA);
    data.taxRates.add(IntakeFixtures.SAAS_18);
    data.declarations.addAll(IntakeFixtures.seedDeclarations());
    return data;
  }

  /** Drops the BU's config, as if it was deactivated after intake started. */
  public FakeReferenceData withoutConfig(String businessUnit) {
    configs.remove(businessUnit);
    return this;
  }

  /** Drops the entity, leaving any {@code bu_entity} row that names it. */
  public FakeReferenceData withoutEntity(String entityCode) {
    entities.remove(entityCode);
    return this;
  }

  /** How many lookups have reached this data so far. */
  public int lookups() {
    return lookups.get();
  }

  @Override
  public Optional<LegalEntity> entity(String entityCode) {
    lookups.incrementAndGet();
    return Optional.ofNullable(entities.get(entityCode));
  }

  @Override
  public Optional<BusinessUnitEntity> businessUnitEntity(String businessUnit, String entityCode) {
    lookups.incrementAndGet();
    return businessUnitEntities.stream()
        .filter(row -> row.businessUnit().equals(businessUnit))
        .filter(row -> row.entityCode().equals(entityCode))
        .findFirst();
  }

  @Override
  public Optional<AdapterConfig> activeConfig(String businessUnit) {
    lookups.incrementAndGet();
    return Optional.ofNullable(configs.get(businessUnit));
  }

  @Override
  public Optional<GstState> gstState(String stateCode) {
    lookups.incrementAndGet();
    return Optional.ofNullable(states.get(stateCode));
  }

  @Override
  public Optional<TaxRate> taxRate(String taxCode, LocalDate date) {
    lookups.incrementAndGet();
    return taxRates.stream()
        .filter(rate -> rate.taxCode().equals(taxCode))
        .filter(rate -> rate.isEffectiveOn(date))
        .findFirst();
  }

  @Override
  public List<Declaration> declarations() {
    lookups.incrementAndGet();
    return List.copyOf(declarations);
  }

  @Override
  public List<AdapterConfig> activeConfigs() {
    lookups.incrementAndGet();
    return List.copyOf(configs.values());
  }

  @Override
  public List<BusinessUnitEntity> businessUnitEntities() {
    lookups.incrementAndGet();
    return List.copyOf(businessUnitEntities);
  }
}
