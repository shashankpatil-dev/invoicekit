package com.shashank.invoicekit.numbering.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks that every part of a series key is present. */
class SeriesKeyTest {

  private static final FinancialYear YEAR = new FinancialYear(2026);
  private static final Shard SHARD = new Shard(0);

  @Test
  void keepsEveryPart() {
    SeriesKey series = new SeriesKey("ENT1", "subscriptions", YEAR, SHARD);

    assertThat(series)
        .hasToString(
            "SeriesKey[entityCode=ENT1, businessUnit=subscriptions, financialYear=2026-27,"
                + " shard=A]");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void entityCodeIsRequired(String entityCode) {
    assertThatThrownBy(() -> new SeriesKey(entityCode, "subscriptions", YEAR, SHARD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("entityCode is required");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void businessUnitIsRequired(String businessUnit) {
    assertThatThrownBy(() -> new SeriesKey("ENT1", businessUnit, YEAR, SHARD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("businessUnit is required");
  }

  @Test
  void financialYearIsRequired() {
    assertThatThrownBy(() -> new SeriesKey("ENT1", "subscriptions", null, SHARD))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("financialYear is required");
  }

  @Test
  void shardIsRequired() {
    assertThatThrownBy(() -> new SeriesKey("ENT1", "subscriptions", YEAR, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("shard is required");
  }
}
