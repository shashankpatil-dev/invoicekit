package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.application.port.outbound.ReferenceData;
import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.GstState;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceContext;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.LineItem;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads the reference data one draft needs into an {@link InvoiceContext}, for the assembler.
 *
 * <p>Missing values are left out instead of rejected here: the assembler rejects an unknown state
 * or tax code with the rest of its rules, so every such reason reads the same way.
 */
public final class InvoiceContextFactory {

  private final ReferenceData referenceData;

  public InvoiceContextFactory(ReferenceData referenceData) {
    this.referenceData = referenceData;
  }

  /** The supplier, place of supply, tax rates on the invoice date, and every declaration. */
  public InvoiceContext contextFor(InvoiceDraft draft, AdapterConfig config) {
    InvoiceRequested event = draft.event();
    return new InvoiceContext(
        supplier(event.entityCode()),
        config,
        placeOfSupplyState(event),
        taxRates(draft),
        referenceData.declarations());
  }

  private LegalEntity supplier(String entityCode) {
    return referenceData
        .entity(entityCode)
        .orElseThrow(
            () ->
                new RejectedEventException(
                    RejectionCode.ENTITY_NOT_ALLOWED,
                    List.of("entity " + entityCode + " does not exist or is inactive")));
  }

  /** An export's place of supply is a country, so it has no GST state. */
  private GstState placeOfSupplyState(InvoiceRequested event) {
    return event.isExport()
        ? null
        : referenceData.gstState(event.placeOfSupplyStateCode()).orElse(null);
  }

  /** One rate per tax code the lines use, chosen for the invoice date. */
  private Map<String, TaxRate> taxRates(InvoiceDraft draft) {
    return draft.lines().stream()
        .map(LineItem::taxCode)
        .distinct()
        .flatMap(taxCode -> referenceData.taxRate(taxCode, draft.event().invoiceDate()).stream())
        .collect(Collectors.toMap(TaxRate::taxCode, Function.identity()));
  }
}
