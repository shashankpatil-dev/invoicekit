package com.shashank.invoicekit.invoice.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.invoice.domain.entity.SupplyCategory;
import com.shashank.invoicekit.invoice.domain.entity.SupplyType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks how the supply category and states decide the supply type. */
class SupplyTypeRuleTest {

  private final SupplyTypeRule rule = new SupplyTypeRule();

  @ParameterizedTest
  @CsvSource({
    "DOMESTIC, 27, 27, INTRA_STATE",
    "DOMESTIC, 27, 29, INTER_STATE",
    "EXPORT_LUT, 27, , EXPORT",
    "EXPORT_LUT, 27, 27, EXPORT"
  })
  void decidesTheSupplyType(
      SupplyCategory category,
      String supplierStateCode,
      String placeOfSupplyStateCode,
      SupplyType supplyType) {
    assertThat(rule.supplyTypeOf(category, supplierStateCode, placeOfSupplyStateCode))
        .isEqualTo(supplyType);
  }
}
