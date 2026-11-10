package com.shashank.invoicekit.invoice.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The saved invoice, exactly as {@code invoice.payload} holds it and the document engine reads it.
 * The shape is the saved payload in the Invoice Superset.
 *
 * <p>Amounts are strings already written to their contract scale, so the JSON never depends on how
 * a mapper prints decimals. Absent optional values are left out of the JSON.
 *
 * @param contractVersion payload contract version, always {@link #CONTRACT_VERSION}
 * @param invoiceId platform ID of the invoice
 * @param businessUnit BU that owns the invoice (JSON key {@code bu})
 * @param sourceEventId the BU's ID for the event
 * @param invoiceNumber number printed on the invoice, null until numbering assigns it
 * @param invoiceDate invoice date
 * @param financialYear financial year of the invoice date, such as {@code 2026-27}
 * @param supplyCategory domestic supply or export under LUT
 * @param supplyType how GST applies
 * @param placeOfSupply state, or country for exports
 * @param reverseCharge whether the recipient pays the tax
 * @param currency ISO currency code
 * @param exchangeRate INR value of one unit of the currency, 4 decimal places
 * @param dueDate payment due date
 * @param paymentTerms payment terms
 * @param poNumber recipient's purchase order number
 * @param declarations declarations that apply, with their final text, in print order
 * @param supplier snapshot of the entity, so later edits never change this invoice
 * @param recipient who the invoice is issued to
 * @param lineItems invoice lines with their tax
 * @param totals invoice totals
 * @param attributes BU extras
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvoicePayload(
    int contractVersion,
    UUID invoiceId,
    @JsonProperty("bu") String businessUnit,
    String sourceEventId,
    String invoiceNumber,
    LocalDate invoiceDate,
    String financialYear,
    SupplyCategory supplyCategory,
    SupplyType supplyType,
    PlaceOfSupply placeOfSupply,
    boolean reverseCharge,
    String currency,
    String exchangeRate,
    LocalDate dueDate,
    String paymentTerms,
    String poNumber,
    List<ResolvedDeclaration> declarations,
    Supplier supplier,
    Recipient recipient,
    List<Line> lineItems,
    Totals totals,
    Map<String, String> attributes) {

  /** The payload contract this record follows. */
  public static final int CONTRACT_VERSION = 2;

  /** A copy with the invoice number, which is assigned only inside the intake transaction. */
  public InvoicePayload withInvoiceNumber(String number) {
    return new InvoicePayload(
        contractVersion,
        invoiceId,
        businessUnit,
        sourceEventId,
        number,
        invoiceDate,
        financialYear,
        supplyCategory,
        supplyType,
        placeOfSupply,
        reverseCharge,
        currency,
        exchangeRate,
        dueDate,
        paymentTerms,
        poNumber,
        declarations,
        supplier,
        recipient,
        lineItems,
        totals,
        attributes);
  }

  /**
   * Where the supply is made: a GST state for domestic supply, or the recipient's country for an
   * export. Only one pair is filled.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PlaceOfSupply(
      String stateName, String stateCode, String countryName, String countryCode) {

    /** A domestic place of supply. */
    public static PlaceOfSupply ofState(GstState state) {
      return new PlaceOfSupply(state.name(), state.code(), null, null);
    }

    /** An export's place of supply, named in English, such as {@code United States}. */
    public static PlaceOfSupply ofCountry(String countryCode) {
      String countryName = Locale.of("", countryCode).getDisplayCountry(Locale.ENGLISH);
      return new PlaceOfSupply(null, null, countryName, countryCode);
    }
  }

  /** A declaration with its placeholders filled in. */
  public record ResolvedDeclaration(String code, String text) {}

  /** The entity's details as they were when the invoice was made. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Supplier(
      String entityCode,
      String legalName,
      String tradeName,
      String gstin,
      String stateName,
      String stateCode,
      String address,
      String registeredOfficeAddress,
      String cin,
      String pan,
      String lutNumber,
      String authorisedSignatory,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> bankDetails,
      String contactEmail,
      String contactPhone,
      String terms) {}

  /** One invoice line with its tax, numbered from 1. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Line(
      int lineNo,
      String description,
      String taxCode,
      String hsnCode,
      String quantity,
      String unit,
      String unitPrice,
      String discount,
      LocalDate servicePeriodStart,
      LocalDate servicePeriodEnd,
      String taxableValue,
      String gstRate,
      String cgstRate,
      String cgstAmount,
      String sgstRate,
      String sgstAmount,
      String igstRate,
      String igstAmount,
      String cessAmount,
      String lineTotal) {}

  /**
   * Invoice totals, each the sum of the rounded line values, in the invoice currency and in INR.
   */
  public record Totals(
      String totalTaxableValue,
      String totalCgst,
      String totalSgst,
      String totalIgst,
      String totalCess,
      String totalTax,
      String totalValue,
      String amountInWords,
      String totalTaxableValueInr,
      String totalValueInr) {}
}
