package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.EXCHANGE_RATE;
import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.MONEY;
import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.RATE;
import static com.shashank.invoicekit.invoice.domain.entity.DecimalPlaces.UNIT_PRICE;

import com.shashank.invoicekit.invoice.domain.entity.AppliedRates;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceContext;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.Line;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.PlaceOfSupply;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.Totals;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.LineItem;
import com.shashank.invoicekit.invoice.domain.entity.SupplyType;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Builds the saved payload from an adapter's draft and the invoice's reference data. Each rule
 * lives in its own class; this one puts their results into the shape of the contract.
 *
 * <p>The payload is built without a number, outside any transaction, so a rejected event never uses
 * one. Missing reference data rejects the event.
 */
public final class InvoiceAssembler {

  private final SupplyTypeRule supplyTypeRule = new SupplyTypeRule();
  private final TaxCalculator taxCalculator = new TaxCalculator();
  private final AmountInWords amountInWords = new AmountInWords();
  private final DeclarationResolver declarationResolver = new DeclarationResolver();
  private final SupplierSnapshot supplierSnapshot = new SupplierSnapshot();

  /** Returns the payload with its invoice number left empty, for numbering to fill in. */
  public InvoicePayload assemble(UUID invoiceId, InvoiceDraft draft, InvoiceContext context) {
    InvoiceRequested event = draft.event();
    LegalEntity supplier = context.supplier();
    requireLutNumberForExport(event, supplier);
    SupplyType supplyType =
        supplyTypeRule.supplyTypeOf(
            event.supplyCategory(), supplier.stateCode(), event.placeOfSupplyStateCode());
    List<LineTax> taxes =
        draft.lines().stream()
            .map(
                line ->
                    taxCalculator.calculate(line, context.taxRateFor(line.taxCode()), supplyType))
            .toList();
    return new InvoicePayload(
        InvoicePayload.CONTRACT_VERSION,
        invoiceId,
        event.businessUnit(),
        event.sourceEventId(),
        null,
        event.invoiceDate(),
        FinancialYear.of(event.invoiceDate()).label(),
        event.supplyCategory(),
        supplyType,
        placeOfSupply(event, context),
        event.reverseCharge(),
        event.currency(),
        EXCHANGE_RATE.format(event.inrExchangeRate()),
        event.dueDate(),
        event.paymentTerms(),
        event.poNumber(),
        declarationResolver.resolve(context.declarations(), event, supplier),
        supplierSnapshot.of(supplier),
        event.recipient(),
        lines(draft.lines(), taxes),
        totals(taxCalculator.total(taxes), event),
        draft.attributes());
  }

  /** An export is made under the supplier's Letter of Undertaking, so it can't go without one. */
  private static void requireLutNumberForExport(InvoiceRequested event, LegalEntity supplier) {
    if (event.isExport() && supplier.lutNumber() == null) {
      throw new RejectedEventException(
          RejectionCode.VALIDATION_FAILED,
          List.of("entity " + supplier.entityCode() + " has no lutNumber, which an export needs"));
    }
  }

  /** The recipient's country for an export, or the GST state of the place of supply otherwise. */
  private static PlaceOfSupply placeOfSupply(InvoiceRequested event, InvoiceContext context) {
    if (event.isExport()) {
      return PlaceOfSupply.ofCountry(event.recipient().countryCode());
    }
    if (context.placeOfSupplyState() == null) {
      throw new RejectedEventException(
          RejectionCode.VALIDATION_FAILED,
          List.of(
              "placeOfSupplyStateCode " + event.placeOfSupplyStateCode() + " is not a GST state"));
    }
    return PlaceOfSupply.ofState(context.placeOfSupplyState());
  }

  private static List<Line> lines(List<LineItem> items, List<LineTax> taxes) {
    return IntStream.range(0, items.size())
        .mapToObj(index -> line(index + 1, items.get(index), taxes.get(index)))
        .toList();
  }

  private static Line line(int lineNo, LineItem item, LineTax tax) {
    AppliedRates rates = tax.rates();
    TaxAmounts amounts = tax.amounts();
    return new Line(
        lineNo,
        item.description(),
        item.taxCode(),
        tax.hsnCode(),
        item.quantity().toPlainString(),
        item.unit(),
        UNIT_PRICE.format(item.unitPrice()),
        MONEY.format(item.discount()),
        item.servicePeriodStart(),
        item.servicePeriodEnd(),
        MONEY.format(amounts.taxableValue()),
        RATE.format(rates.gstRate()),
        RATE.format(rates.cgstRate()),
        MONEY.format(amounts.cgst()),
        RATE.format(rates.sgstRate()),
        MONEY.format(amounts.sgst()),
        RATE.format(rates.igstRate()),
        MONEY.format(amounts.igst()),
        MONEY.format(amounts.cess()),
        MONEY.format(amounts.total()));
  }

  private Totals totals(TaxAmounts totals, InvoiceRequested event) {
    BigDecimal exchangeRate = event.inrExchangeRate();
    return new Totals(
        MONEY.format(totals.taxableValue()),
        MONEY.format(totals.cgst()),
        MONEY.format(totals.sgst()),
        MONEY.format(totals.igst()),
        MONEY.format(totals.cess()),
        MONEY.format(totals.tax()),
        MONEY.format(totals.total()),
        amountInWords.inWords(totals.total(), event.currency()),
        MONEY.format(taxCalculator.inInr(totals.taxableValue(), exchangeRate)),
        MONEY.format(taxCalculator.inInr(totals.total(), exchangeRate)));
  }
}
