package com.shashank.invoicekit.invoice.application.adaption;

import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceField;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Keeps each BU to the optional fields its config allows, so nothing unexpected reaches a saved
 * invoice. It checks the draft, which is what the payload is built from.
 */
public final class AllowedFieldsCheck {

  /** Rejects the draft, listing every offending field, when it uses one the BU may not send. */
  public void check(InvoiceDraft draft, AdapterConfig config) {
    List<String> problems =
        Stream.concat(unknownAttributes(draft), disallowedFields(draft, config)).toList();
    if (!problems.isEmpty()) {
      throw new RejectedEventException(RejectionCode.VALIDATION_FAILED, problems);
    }
  }

  private static Stream<String> unknownAttributes(InvoiceDraft draft) {
    return draft.attributes().keySet().stream()
        .filter(key -> InvoiceField.forAttributeKey(key).isEmpty())
        .map(key -> "attribute " + key + " is not a known invoice field");
  }

  private static Stream<String> disallowedFields(InvoiceDraft draft, AdapterConfig config) {
    return Arrays.stream(InvoiceField.values())
        .filter(field -> field.isUsedBy(draft))
        .filter(Predicate.not(config::allows))
        .map(field -> field.key() + " is not allowed for business unit " + config.businessUnit());
  }
}
