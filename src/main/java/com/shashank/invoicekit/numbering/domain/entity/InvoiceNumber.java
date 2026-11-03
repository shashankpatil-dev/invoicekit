package com.shashank.invoicekit.numbering.domain.entity;

/**
 * A number assigned to one invoice.
 *
 * @param series series the number was taken from
 * @param sequenceNo position in that series, starting at 1
 * @param value formatted number printed on the invoice, such as {@code SUBA260000000142}
 */
public record InvoiceNumber(SeriesKey series, long sequenceNo, String value) {}
