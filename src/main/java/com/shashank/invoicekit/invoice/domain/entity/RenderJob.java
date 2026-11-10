package com.shashank.invoicekit.invoice.domain.entity;

import com.shashank.invoicekit.documentengine.domain.entity.RenderRequest;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One invoice to render, read from its {@code invoice.render} message. It carries everything the
 * render needs, including the financial year, so every statement on the partitioned {@code invoice}
 * table goes straight to the invoice's own partition.
 *
 * @param invoiceId the invoice's ID
 * @param businessUnit business unit that owns the invoice, whose template renders it
 * @param financialYear financial year of the invoice, such as {@code 2026-27}, which is the
 *     partition key
 * @param invoiceDate invoice date, which places the PDF in storage
 * @param invoiceNumber number printed on the invoice
 * @param payloadJson saved invoice payload, passed to the document engine unchanged
 */
public record RenderJob(
    UUID invoiceId,
    String businessUnit,
    String financialYear,
    LocalDate invoiceDate,
    String invoiceNumber,
    String payloadJson) {

  public RenderJob {
    Objects.requireNonNull(invoiceId, "invoiceId is required");
    requireText(businessUnit, "businessUnit");
    FinancialYear.parse(financialYear);
    Objects.requireNonNull(invoiceDate, "invoiceDate is required");
    requireText(invoiceNumber, "invoiceNumber");
    requireText(payloadJson, "payloadJson");
  }

  /**
   * What the document engine is asked to render: the payload, with its business unit's template.
   */
  public RenderRequest renderRequest() {
    return new RenderRequest(businessUnit, payloadJson);
  }

  /** Where this invoice's PDF is stored. */
  public DocumentPath documentPath() {
    return new DocumentPath(businessUnit, invoiceDate, invoiceId);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
