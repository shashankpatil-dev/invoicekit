package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.domain.entity.Declaration;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.ResolvedDeclaration;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the declarations that apply to an invoice and writes their final text. The text is saved in
 * the payload, so changing a declaration's wording later never changes an old invoice.
 */
final class DeclarationResolver {

  /** A placeholder such as {@code {lutNumber}}, naming one of the supplier's fields. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

  /** Print order, with the code breaking ties so the order never depends on loading order. */
  private static final Comparator<Declaration> PRINT_ORDER =
      Comparator.comparingInt(Declaration::sortOrder).thenComparing(Declaration::code);

  /**
   * Returns the matching declarations in print order, rejecting the event when any placeholder has
   * no value, because a legal statement must never print with a gap.
   */
  List<ResolvedDeclaration> resolve(
      List<Declaration> candidates, InvoiceRequested invoice, LegalEntity supplier) {
    Map<String, String> values = supplier.placeholderValues();
    List<String> unresolved = new ArrayList<>();
    List<ResolvedDeclaration> resolved = new ArrayList<>();
    for (Declaration declaration :
        candidates.stream().filter(d -> d.appliesTo(invoice)).sorted(PRINT_ORDER).toList()) {
      resolved.add(
          new ResolvedDeclaration(declaration.code(), fill(declaration, values, unresolved)));
    }
    if (!unresolved.isEmpty()) {
      throw new RejectedEventException(RejectionCode.VALIDATION_FAILED, unresolved);
    }
    return List.copyOf(resolved);
  }

  /** Replaces each placeholder with its value, noting any that has none. */
  private static String fill(
      Declaration declaration, Map<String, String> values, List<String> unresolved) {
    return PLACEHOLDER
        .matcher(declaration.body())
        .replaceAll(
            placeholder -> {
              String value = values.get(placeholder.group(1));
              if (value == null) {
                unresolved.add(
                    "declaration "
                        + declaration.code()
                        + " has no value for "
                        + placeholder.group());
                return Matcher.quoteReplacement(placeholder.group());
              }
              return Matcher.quoteReplacement(value);
            });
  }
}
