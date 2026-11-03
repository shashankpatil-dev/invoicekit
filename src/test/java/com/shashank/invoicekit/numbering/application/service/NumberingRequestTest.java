package com.shashank.invoicekit.numbering.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.numbering.domain.entity.NumberingRule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks that a numbering request has everything needed to choose a series. */
class NumberingRequestTest {

  private static final LocalDate DATE = LocalDate.of(2026, 11, 4);
  private static final NumberingRule RULE =
      new NumberingRule(NumberFormat.parse("SUB{shard}{fy}{seq:10}"), 1);

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void textFieldsAreRequired(String missing) {
    assertThatThrownBy(() -> new NumberingRequest(missing, "subscriptions", "event-1", DATE, RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("entityCode is required");
    assertThatThrownBy(() -> new NumberingRequest("ENT1", missing, "event-1", DATE, RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("businessUnit is required");
    assertThatThrownBy(() -> new NumberingRequest("ENT1", "subscriptions", missing, DATE, RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sourceEventId is required");
  }

  @Test
  void invoiceDateIsRequired() {
    assertThatThrownBy(() -> new NumberingRequest("ENT1", "subscriptions", "event-1", null, RULE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("invoiceDate is required");
  }

  @Test
  void ruleIsRequired() {
    assertThatThrownBy(() -> new NumberingRequest("ENT1", "subscriptions", "event-1", DATE, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("rule is required");
  }
}
