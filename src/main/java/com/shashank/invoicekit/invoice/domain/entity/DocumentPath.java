package com.shashank.invoicekit.invoice.domain.entity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Where an invoice's PDF is stored: {@code
 * invoices/<businessUnit>/<yyyy>/<MM>/<dd>/<invoiceId>.pdf}. Grouping by business unit and invoice
 * date keeps one day's PDFs together for the daily archive.
 *
 * @param businessUnit business unit that owns the invoice
 * @param invoiceDate invoice date, which picks the year, month and day folders
 * @param invoiceId the invoice's ID, which names the file
 */
public record DocumentPath(String businessUnit, LocalDate invoiceDate, UUID invoiceId) {

  private static final DateTimeFormatter DATE_FOLDERS = DateTimeFormatter.ofPattern("yyyy/MM/dd");

  public DocumentPath {
    if (businessUnit == null || businessUnit.isBlank()) {
      throw new IllegalArgumentException("businessUnit is required");
    }
    Objects.requireNonNull(invoiceDate, "invoiceDate is required");
    Objects.requireNonNull(invoiceId, "invoiceId is required");
  }

  /** The full object path inside the bucket. */
  public String value() {
    return "invoices/%s/%s/%s.pdf"
        .formatted(businessUnit, invoiceDate.format(DATE_FOLDERS), invoiceId);
  }
}
