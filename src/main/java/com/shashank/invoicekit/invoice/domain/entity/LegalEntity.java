package com.shashank.invoicekit.invoice.domain.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The supplier: one row in {@code entity}, which is one GSTIN. Many BUs can invoice from it.
 *
 * @param entityCode internal key, such as {@code ACME_MH}
 * @param legalName registered company name
 * @param tradeName name shown when it differs from the legal name
 * @param gstin the entity's GSTIN
 * @param stateName state of the GSTIN
 * @param stateCode two-digit GST state code of the GSTIN
 * @param address address for this GSTIN, including PIN
 * @param registeredOfficeAddress registered office of the company
 * @param cin company identification number
 * @param pan PAN, shown by choice
 * @param lutNumber Letter of Undertaking reference, needed for exports
 * @param authorisedSignatory signatory name, not needed on electronic invoices
 * @param bankDetails bank details shown for payment, in display order; empty when absent
 * @param contactEmail support email
 * @param contactPhone support phone
 * @param terms standard invoice terms
 */
public record LegalEntity(
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
    Map<String, String> bankDetails,
    String contactEmail,
    String contactPhone,
    String terms) {

  public LegalEntity {
    bankDetails = ImmutableCopies.orderedMapOf(bankDetails);
  }

  /**
   * The values declaration wording may use, keyed by field name, such as {@code lutNumber}. Fields
   * without a value are left out, so a placeholder that needs them stays unresolved.
   */
  public Map<String, String> placeholderValues() {
    Map<String, String> values = new HashMap<>();
    values.put("entityCode", entityCode);
    values.put("legalName", legalName);
    values.put("tradeName", tradeName);
    values.put("gstin", gstin);
    values.put("stateName", stateName);
    values.put("stateCode", stateCode);
    values.put("address", address);
    values.put("registeredOfficeAddress", registeredOfficeAddress);
    values.put("cin", cin);
    values.put("pan", pan);
    values.put("lutNumber", lutNumber);
    values.put("authorisedSignatory", authorisedSignatory);
    values.put("contactEmail", contactEmail);
    values.put("contactPhone", contactPhone);
    values.put("terms", terms);
    values.values().removeIf(Objects::isNull);
    return values;
  }
}
