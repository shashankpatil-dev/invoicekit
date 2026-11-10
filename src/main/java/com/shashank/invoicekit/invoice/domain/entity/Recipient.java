package com.shashank.invoicekit.invoice.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Who the invoice is issued to, as the BU sends it. The saved payload copies it unchanged.
 *
 * @param name recipient's name
 * @param gstin GSTIN, present only for a recipient registered in India
 * @param foreignTaxId tax ID shown for a foreign recipient
 * @param address billing address
 * @param stateName state, for Indian recipients
 * @param stateCode two-digit GST state code, for Indian recipients
 * @param countryCode ISO country code, required for exports
 * @param pin postal code
 * @param email address the invoice notification is sent to
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Recipient(
    String name,
    String gstin,
    String foreignTaxId,
    String address,
    String stateName,
    String stateCode,
    String countryCode,
    String pin,
    String email) {

  /** Whether the recipient is registered for GST in India, which some declarations depend on. */
  @JsonIgnore
  public boolean isRegistered() {
    return gstin != null;
  }
}
