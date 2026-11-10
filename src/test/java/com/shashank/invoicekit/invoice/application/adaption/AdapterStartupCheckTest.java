package com.shashank.invoicekit.invoice.application.adaption;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks every reason intake refuses to start, and that all of them are reported together. */
class AdapterStartupCheckTest {

  private static final List<StubAdapter> ADAPTERS =
      List.of(new StubAdapter("subscriptions"), new StubAdapter("usage"));
  private static final List<AdapterConfig> CONFIGS =
      List.of(
          config("subscriptions", "SUB{shard}{fy}{seq:10}"), config("usage", "USG{fy}{seq:10}"));

  private final AdapterStartupCheck check = new AdapterStartupCheck();

  @Test
  void acceptsAMatchingSetup() {
    List<BusinessUnitEntity> rows =
        List.of(
            new BusinessUnitEntity("subscriptions", "ACME_MH", 4),
            new BusinessUnitEntity("usage", "ACME_MH", 1));

    assertThatCode(() -> check.verify(ADAPTERS, CONFIGS, rows)).doesNotThrowAnyException();
  }

  @Test
  void refusesAnAdapterWithoutAConfig() {
    assertRefused(
        List.of(
            new StubAdapter("subscriptions"),
            new StubAdapter("usage"),
            new StubAdapter("enterprise")),
        CONFIGS,
        List.of(),
        "business unit enterprise has an adapter but no config");
  }

  @Test
  void refusesAConfigWithoutAnAdapter() {
    assertRefused(
        List.of(new StubAdapter("subscriptions")),
        CONFIGS,
        List.of(),
        "business unit usage has a config but no adapter");
  }

  @Test
  void refusesTwoBusinessUnitsSharingAPrefixOnOneEntity() {
    List<AdapterConfig> configs =
        List.of(config("subscriptions", "SUB{fy}{seq:10}"), config("usage", "SUB{seq:10}"));

    assertRefused(
        ADAPTERS,
        configs,
        List.of(
            new BusinessUnitEntity("subscriptions", "ACME_MH", 1),
            new BusinessUnitEntity("usage", "ACME_MH", 1)),
        "business units [subscriptions, usage] share number prefix 'SUB' on entity ACME_MH");
  }

  @Test
  void allowsTheSamePrefixOnDifferentEntities() {
    List<AdapterConfig> configs =
        List.of(config("subscriptions", "SUB{fy}{seq:10}"), config("usage", "SUB{seq:10}"));
    List<BusinessUnitEntity> rows =
        List.of(
            new BusinessUnitEntity("subscriptions", "ACME_MH", 1),
            new BusinessUnitEntity("usage", "ACME_KA", 1));

    assertThatCode(() -> check.verify(ADAPTERS, configs, rows)).doesNotThrowAnyException();
  }

  @Test
  void refusesSeveralShardsWithoutAShardLetter() {
    assertRefused(
        ADAPTERS,
        CONFIGS,
        List.of(new BusinessUnitEntity("usage", "ACME_MH", 2)),
        "business unit usage uses 2 shards on entity ACME_MH but its number format"
            + " USG{fy}{seq:10} has no {shard}");
  }

  @Test
  void ignoresEntityRowsForBusinessUnitsWithoutAConfig() {
    List<BusinessUnitEntity> rows =
        List.of(
            new BusinessUnitEntity("enterprise", "ACME_MH", 3),
            new BusinessUnitEntity("subscriptions", "ACME_MH", 1));

    assertThatCode(() -> check.verify(ADAPTERS, CONFIGS, rows)).doesNotThrowAnyException();
  }

  @Test
  void reportsEveryProblemTogether() {
    List<AdapterConfig> configs =
        List.of(
            config("subscriptions", "SUB{fy}{seq:10}"),
            config("usage", "SUB{seq:10}"),
            config("services", "SVC{seq:10}"));

    assertRefused(
        List.of(
            new StubAdapter("subscriptions"),
            new StubAdapter("usage"),
            new StubAdapter("enterprise")),
        configs,
        List.of(
            new BusinessUnitEntity("subscriptions", "ACME_MH", 2),
            new BusinessUnitEntity("usage", "ACME_MH", 1)),
        "business unit enterprise has an adapter but no config",
        "business unit services has a config but no adapter",
        "business units [subscriptions, usage] share number prefix 'SUB' on entity ACME_MH",
        "business unit subscriptions uses 2 shards on entity ACME_MH but its number format"
            + " SUB{fy}{seq:10} has no {shard}");
  }

  private void assertRefused(
      List<StubAdapter> adapters,
      List<AdapterConfig> configs,
      List<BusinessUnitEntity> rows,
      String... problems) {
    assertThatThrownBy(() -> check.verify(adapters, configs, rows))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Intake cannot start:\n- " + String.join("\n- ", problems));
  }

  private static AdapterConfig config(String businessUnit, String numberFormat) {
    return new AdapterConfig(businessUnit, 1, Set.of(), NumberFormat.parse(numberFormat));
  }
}
