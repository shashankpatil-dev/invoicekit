package com.shashank.invoicekit.invoice.application.port.outbound;

import com.shashank.invoicekit.invoice.domain.entity.InvoiceStatus;
import com.shashank.invoicekit.invoice.domain.entity.RenderJob;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves an invoice through rendering: reads its status, then finishes it as {@code STORED} or marks
 * it {@code FAILED}. Both moves happen only from {@code NUMBERED}, so a resent message or a second
 * worker can never move an invoice twice.
 */
public interface InvoiceLifecycleStore {

  /**
   * The invoice's current status.
   *
   * @return the status, or empty when no invoice has this ID in this financial year
   */
  Optional<InvoiceStatus> status(UUID invoiceId, String financialYear);

  /**
   * Marks a numbered invoice {@code STORED} with its PDF's URI and checksum. Its audit row and its
   * {@code invoice.generated} outbox row are written with it, or not at all.
   *
   * @return whether the invoice was still {@code NUMBERED} and is now {@code STORED}
   */
  boolean finish(RenderJob job, StoredDocument document);

  /**
   * Marks a numbered invoice {@code FAILED}, keeping the reason in its audit row.
   *
   * @return whether the invoice was still {@code NUMBERED} and is now {@code FAILED}
   */
  boolean markFailed(RenderJob job, String reason);
}
