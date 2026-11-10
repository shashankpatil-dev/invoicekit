package com.shashank.invoicekit.invoice.application.adaption;

import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Refuses to start intake when adapters and reference data disagree, so a bad setup fails at deploy
 * time instead of sending events to the dead-letter topic.
 *
 * <p>Configs reach this check with their fields and number format already parsed, so unknown field
 * names and broken formats are reported by the loader. Duplicate adapters are refused by {@link
 * AdapterRegistry}.
 */
public final class AdapterStartupCheck {

  /** Throws {@link IllegalStateException} listing every problem found. */
  public void verify(
      Collection<? extends InvoiceAdapter> adapters,
      Collection<AdapterConfig> activeConfigs,
      Collection<BusinessUnitEntity> businessUnitEntities) {
    Set<String> adapterBusinessUnits =
        adapters.stream()
            .map(InvoiceAdapter::businessUnit)
            .collect(Collectors.toCollection(TreeSet::new));
    // Sorted so problems are always listed in the same order. A BU has one active config, which
    // the database guarantees, so a second one fails here as a duplicate key.
    Map<String, AdapterConfig> configs =
        new TreeMap<>(
            activeConfigs.stream()
                .collect(Collectors.toMap(AdapterConfig::businessUnit, Function.identity())));
    String problems =
        Stream.of(
                adaptersWithoutConfig(adapterBusinessUnits, configs),
                configsWithoutAdapter(adapterBusinessUnits, configs),
                sharedPrefixes(businessUnitEntities, configs),
                shardsWithoutShardToken(businessUnitEntities, configs))
            .flatMap(Function.identity())
            .collect(Collectors.joining("\n- "));
    if (!problems.isEmpty()) {
      throw new IllegalStateException("Intake cannot start:\n- " + problems);
    }
  }

  private static Stream<String> adaptersWithoutConfig(
      Set<String> adapterBusinessUnits, Map<String, AdapterConfig> configs) {
    return adapterBusinessUnits.stream()
        .filter(businessUnit -> !configs.containsKey(businessUnit))
        .map(businessUnit -> "business unit " + businessUnit + " has an adapter but no config");
  }

  private static Stream<String> configsWithoutAdapter(
      Set<String> adapterBusinessUnits, Map<String, AdapterConfig> configs) {
    return configs.keySet().stream()
        .filter(businessUnit -> !adapterBusinessUnits.contains(businessUnit))
        .map(businessUnit -> "business unit " + businessUnit + " has a config but no adapter");
  }

  /**
   * BUs that share an entity also share its GSTIN, so their numbers must differ by prefix, or two
   * invoices from one GSTIN could carry the same number.
   */
  private static Stream<String> sharedPrefixes(
      Collection<BusinessUnitEntity> businessUnitEntities, Map<String, AdapterConfig> configs) {
    Map<String, Map<String, Set<String>>> businessUnitsByPrefixByEntity =
        businessUnitEntities.stream()
            .filter(row -> configs.containsKey(row.businessUnit()))
            .collect(
                Collectors.groupingBy(
                    BusinessUnitEntity::entityCode,
                    TreeMap::new,
                    Collectors.groupingBy(
                        row -> configs.get(row.businessUnit()).numberFormat().leadingLiteral(),
                        TreeMap::new,
                        Collectors.mapping(
                            BusinessUnitEntity::businessUnit,
                            Collectors.toCollection(TreeSet::new)))));
    return businessUnitsByPrefixByEntity.entrySet().stream()
        .flatMap(
            entity ->
                entity.getValue().entrySet().stream()
                    .filter(prefix -> prefix.getValue().size() > 1)
                    .map(
                        prefix ->
                            "business units "
                                + prefix.getValue()
                                + " share number prefix '"
                                + prefix.getKey()
                                + "' on entity "
                                + entity.getKey()));
  }

  /** Every shard needs its own letter in the number, or the shards would repeat each other. */
  private static Stream<String> shardsWithoutShardToken(
      Collection<BusinessUnitEntity> businessUnitEntities, Map<String, AdapterConfig> configs) {
    return businessUnitEntities.stream()
        .filter(row -> row.numberShards() > 1)
        .filter(row -> configs.containsKey(row.businessUnit()))
        .filter(row -> !configs.get(row.businessUnit()).numberFormat().usesShard())
        .map(
            row ->
                "business unit "
                    + row.businessUnit()
                    + " uses "
                    + row.numberShards()
                    + " shards on entity "
                    + row.entityCode()
                    + " but its number format "
                    + configs.get(row.businessUnit()).numberFormat()
                    + " has no {shard}");
  }
}
