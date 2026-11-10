package com.shashank.invoicekit.invoice.domain.entity;

import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import java.util.Set;

/**
 * A BU's active row in {@code adapter_config}: which optional fields it may send and how its
 * invoices are numbered.
 *
 * @param businessUnit BU the config belongs to
 * @param version config version, kept so older rows stay for reference
 * @param allowedFields optional fields the BU may send
 * @param numberFormat invoice number template, such as {@code SUB{shard}{fy}{seq:10}}
 */
public record AdapterConfig(
    String businessUnit, int version, Set<InvoiceField> allowedFields, NumberFormat numberFormat) {

  public AdapterConfig {
    allowedFields = Set.copyOf(allowedFields);
  }

  /** Whether the BU may send this field. */
  public boolean allows(InvoiceField field) {
    return allowedFields.contains(field);
  }
}
