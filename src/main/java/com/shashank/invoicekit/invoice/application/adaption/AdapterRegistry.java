package com.shashank.invoicekit.invoice.application.adaption;

import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Finds the adapter for an event's BU. Built once at startup from every adapter found. */
public final class AdapterRegistry {

  private final Map<String, InvoiceAdapter> adaptersByBusinessUnit;

  /** Fails when two adapters claim one BU, because events could not tell which to use. */
  public AdapterRegistry(Collection<? extends InvoiceAdapter> adapters) {
    this.adaptersByBusinessUnit =
        adapters.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    InvoiceAdapter::businessUnit,
                    Function.identity(),
                    AdapterRegistry::refuseSecondAdapter));
  }

  /** The adapter for a BU, rejecting the event when no adapter serves it. */
  public InvoiceAdapter forBusinessUnit(String businessUnit) {
    InvoiceAdapter adapter = adaptersByBusinessUnit.get(businessUnit);
    if (adapter == null) {
      throw new RejectedEventException(
          RejectionCode.UNKNOWN_BU, List.of("No adapter serves business unit " + businessUnit));
    }
    return adapter;
  }

  private static InvoiceAdapter refuseSecondAdapter(InvoiceAdapter first, InvoiceAdapter second) {
    throw new IllegalStateException(
        "Business unit "
            + first.businessUnit()
            + " has two adapters: "
            + first.getClass().getName()
            + " and "
            + second.getClass().getName());
  }
}
