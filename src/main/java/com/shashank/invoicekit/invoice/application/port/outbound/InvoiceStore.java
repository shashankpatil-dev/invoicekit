package com.shashank.invoicekit.invoice.application.port.outbound;

import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import java.util.UUID;

/**
 * Saves invoices for intake. Both writes run inside the caller's transaction, so a claimed event,
 * its number and its invoice commit or roll back together.
 */
public interface InvoiceStore {

  /**
   * Claims an event for one invoice, as the first write of the intake transaction.
   *
   * @return true when this delivery claimed the event, false when an earlier one already had it
   */
  boolean claimEvent(
      String businessUnit, String sourceEventId, UUID invoiceId, String financialYear);

  /**
   * Saves the numbered invoice, its first audit row and its {@code invoice.render} outbox row, all
   * in one statement, as the last write before commit.
   *
   * @param invoice the payload, already carrying its invoice number
   * @param number the number it carries, with its series and sequence
   */
  void saveNumbered(InvoicePayload invoice, InvoiceNumber number);

  /** How many bytes the payload takes once saved, which is also its size in the render message. */
  int payloadBytes(InvoicePayload invoice);
}
