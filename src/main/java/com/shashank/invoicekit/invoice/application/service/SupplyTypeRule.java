package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.domain.entity.SupplyCategory;
import com.shashank.invoicekit.invoice.domain.entity.SupplyType;

/**
 * Decides how GST applies: an export is zero-rated, and a domestic supply is intra-state when the
 * supplier's GSTIN is in the place-of-supply state, or inter-state otherwise.
 */
final class SupplyTypeRule {

  SupplyType supplyTypeOf(
      SupplyCategory category, String supplierStateCode, String placeOfSupplyStateCode) {
    if (category == SupplyCategory.EXPORT_LUT) {
      return SupplyType.EXPORT;
    }
    return supplierStateCode.equals(placeOfSupplyStateCode)
        ? SupplyType.INTRA_STATE
        : SupplyType.INTER_STATE;
  }
}
