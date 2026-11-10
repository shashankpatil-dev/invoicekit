package com.shashank.invoicekit.invoice.domain.entity;

/**
 * An invoice PDF saved in object storage.
 *
 * @param uri where the PDF lives, such as {@code gs://invoices/invoices/subscriptions/...pdf}
 * @param checksum CRC32C of the stored bytes in the form storage reports it, such as {@code
 *     crc32c:AAAAAA==}
 */
public record StoredDocument(String uri, String checksum) {}
