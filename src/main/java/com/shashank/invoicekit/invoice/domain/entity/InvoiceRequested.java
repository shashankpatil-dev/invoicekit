package com.shashank.invoicekit.invoice.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The {@code invoice.requested} event a BU sends, following contract v2 in the Invoice Superset. It
 * holds transaction facts only: the platform adds supplier data, tax and all calculated values.
 *
 * @param schemaVersion contract version the BU wrote, which must be 2
 * @param businessUnit BU that sent the event, which selects the adapter (JSON key {@code bu})
 * @param sourceEventId the BU's ID for the event, used to reject duplicates
 * @param entityCode entity (GSTIN holder) issuing the invoice
 * @param invoiceDate invoice date, which sets the financial year and tax rates
 * @param supplyCategory domestic supply or export under LUT
 * @param placeOfSupplyStateCode GST state code of the place of supply, for domestic supply
 * @param reverseCharge whether the recipient pays the tax; always false for exports
 * @param currency ISO currency code, {@code INR} for domestic supply
 * @param exchangeRate INR value of one unit of {@code currency}, needed unless it is INR
 * @param dueDate payment due date, if the BU allows it
 * @param paymentTerms payment terms, if the BU allows them
 * @param poNumber recipient's purchase order number, if the BU allows it
 * @param recipient who the invoice is issued to
 * @param lineItems invoice lines, never null
 * @param attributes BU-specific extras, limited to the BU's allowed fields, never null
 */
public record InvoiceRequested(
    int schemaVersion,
    @JsonProperty("bu") String businessUnit,
    String sourceEventId,
    String entityCode,
    LocalDate invoiceDate,
    SupplyCategory supplyCategory,
    String placeOfSupplyStateCode,
    Boolean reverseCharge,
    String currency,
    BigDecimal exchangeRate,
    LocalDate dueDate,
    String paymentTerms,
    String poNumber,
    Recipient recipient,
    List<LineItem> lineItems,
    Map<String, String> attributes) {

  /** Currency of every domestic invoice, whose amounts need no conversion. */
  public static final String INR = "INR";

  public InvoiceRequested {
    lineItems = ImmutableCopies.listOf(lineItems);
    attributes = ImmutableCopies.orderedMapOf(attributes);
  }

  /** Whether this is an export under LUT. */
  @JsonIgnore
  public boolean isExport() {
    return supplyCategory == SupplyCategory.EXPORT_LUT;
  }

  /** The rate that converts invoice amounts to INR, which is 1 when the BU sends none for INR. */
  public BigDecimal inrExchangeRate() {
    return exchangeRate == null ? BigDecimal.ONE : exchangeRate;
  }
}
