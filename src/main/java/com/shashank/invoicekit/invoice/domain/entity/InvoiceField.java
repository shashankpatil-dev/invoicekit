package com.shashank.invoicekit.invoice.domain.entity;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The catalog of optional invoice fields. A BU may send one only when its config lists it in {@code
 * allowed_fields}.
 *
 * <p>The first three are top-level event fields. The rest travel in the event's {@code attributes},
 * under their attribute key.
 */
public enum InvoiceField {
  DUE_DATE("dueDate", draft -> draft.event().dueDate() != null),
  PAYMENT_TERMS("paymentTerms", draft -> draft.event().paymentTerms() != null),
  PO_NUMBER("poNumber", draft -> draft.event().poNumber() != null),
  SUBSCRIPTION_ID("subscriptionId"),
  PLAN_NAME("planName"),
  BILLING_CYCLE("billingCycle"),
  COUPON_CODE("couponCode"),
  USAGE_SUMMARY("usageSummary"),
  CONTRACT_REF("contractRef"),
  MILESTONE("milestone"),
  ADDON_ID("addonId"),
  DEVELOPER_ID("developerId");

  private static final Map<String, InvoiceField> BY_ATTRIBUTE_KEY =
      Arrays.stream(values())
          .filter(field -> field.attribute)
          .collect(Collectors.toUnmodifiableMap(InvoiceField::key, Function.identity()));

  private final String key;
  private final boolean attribute;
  private final Predicate<InvoiceDraft> usedBy;

  /** A top-level event field, found by its own accessor. */
  InvoiceField(String key, Predicate<InvoiceDraft> usedBy) {
    this.key = key;
    this.attribute = false;
    this.usedBy = usedBy;
  }

  /** A field that travels in {@code attributes} under this key. */
  InvoiceField(String attributeKey) {
    this.key = attributeKey;
    this.attribute = true;
    this.usedBy = draft -> draft.attributes().containsKey(attributeKey);
  }

  /** Finds the field an attribute key belongs to, or nothing for a key outside the catalog. */
  public static Optional<InvoiceField> forAttributeKey(String attributeKey) {
    return Optional.ofNullable(BY_ATTRIBUTE_KEY.get(attributeKey));
  }

  /** The field's JSON name, such as {@code dueDate} or {@code planName}. */
  public String key() {
    return key;
  }

  /** The key under {@code attributes}, or nothing for a top-level field. */
  public Optional<String> attributeKey() {
    return attribute ? Optional.of(key) : Optional.empty();
  }

  /** Whether a draft carries a value for this field. */
  public boolean isUsedBy(InvoiceDraft draft) {
    return usedBy.test(draft);
  }
}
