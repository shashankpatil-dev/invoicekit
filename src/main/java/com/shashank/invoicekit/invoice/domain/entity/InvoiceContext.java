package com.shashank.invoicekit.invoice.domain.entity;

import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import java.util.Map;

/**
 * The reference data one invoice needs, loaded before the payload is assembled.
 *
 * @param supplier entity issuing the invoice
 * @param config the BU's active adapter config
 * @param placeOfSupplyState GST state of the place of supply, null for exports or an unknown code
 * @param taxRates rates by tax code, already chosen for the invoice date
 * @param declarations every candidate declaration; the resolver picks the ones that apply
 */
public record InvoiceContext(
    LegalEntity supplier,
    AdapterConfig config,
    GstState placeOfSupplyState,
    Map<String, TaxRate> taxRates,
    List<Declaration> declarations) {

  public InvoiceContext {
    taxRates = Map.copyOf(taxRates);
    declarations = List.copyOf(declarations);
  }

  /** The rate for a line's tax code, rejecting the event when the code has none on its date. */
  public TaxRate taxRateFor(String taxCode) {
    TaxRate taxRate = taxRates.get(taxCode);
    if (taxRate == null) {
      throw new RejectedEventException(
          RejectionCode.VALIDATION_FAILED,
          List.of("taxCode " + taxCode + " has no rate on the invoice date"));
    }
    return taxRate;
  }
}
