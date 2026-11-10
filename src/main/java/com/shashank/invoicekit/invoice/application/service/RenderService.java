package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import com.shashank.invoicekit.documentengine.domain.entity.RenderedDocument;
import com.shashank.invoicekit.documentengine.domain.exception.RenderRejectedException;
import com.shashank.invoicekit.invoice.application.port.outbound.DocumentStore;
import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceLifecycleStore;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceStatus;
import com.shashank.invoicekit.invoice.domain.entity.RenderJob;
import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns one numbered invoice into a stored PDF, following steps 2 to 5 of docs/RENDER.md section 3:
 * check the status, render, store the PDF, then finish the invoice.
 *
 * <p>It holds no database transaction while rendering or storing. Each write to the invoice is one
 * guarded statement, so a resent message or a second worker never moves an invoice twice.
 *
 * <p>An unavailable engine, storage failures and database failures are thrown unchanged, so the
 * caller can retry the whole render. A retry is safe, because the upload is create-only and the
 * finish happens only from {@code NUMBERED}.
 */
public final class RenderService {

  private static final Logger log = LoggerFactory.getLogger(RenderService.class);

  /** The invoice left {@code NUMBERED} while this worker was rendering it. */
  private static final RenderOutcome MOVED_ON =
      new RenderOutcome.Skipped("another worker moved it on from NUMBERED first");

  private final InvoiceLifecycleStore lifecycle;
  private final DocumentEngine engine;
  private final DocumentStore documents;

  public RenderService(
      InvoiceLifecycleStore lifecycle, DocumentEngine engine, DocumentStore documents) {
    this.lifecycle = lifecycle;
    this.engine = engine;
    this.documents = documents;
  }

  /**
   * Renders and stores the invoice's PDF, unless it was done already.
   *
   * @throws com.shashank.invoicekit.documentengine.domain.exception.RenderUnavailableException when
   *     the engine can't render now, so the render can be retried
   */
  public RenderOutcome render(RenderJob job) {
    RenderOutcome outcome = outcomeOf(job);
    log.atLevel(outcome.logLevel())
        .log("{} invoice {} {}", job.businessUnit(), job.invoiceNumber(), outcome.summary());
    return outcome;
  }

  /** A missing invoice, or one past {@code NUMBERED}, needs no PDF, so nothing is rendered. */
  private RenderOutcome outcomeOf(RenderJob job) {
    Optional<InvoiceStatus> status = lifecycle.status(job.invoiceId(), job.financialYear());
    if (status.filter(InvoiceStatus::awaitsRender).isEmpty()) {
      return new RenderOutcome.Skipped(
          status.map(current -> "it is already " + current).orElse("it does not exist"));
    }
    return renderAndStore(job);
  }

  /** A rejected render fails the same way every time, so the invoice is failed straight away. */
  private RenderOutcome renderAndStore(RenderJob job) {
    RenderedDocument document;
    try {
      document = engine.render(job.renderRequest());
    } catch (RenderRejectedException rejected) {
      return markFailed(job, rejected.getMessage());
    }
    return finish(job, documents.storeIfAbsent(job.documentPath(), document.pdf()));
  }

  private RenderOutcome finish(RenderJob job, StoredDocument stored) {
    return lifecycle.finish(job, stored) ? new RenderOutcome.Stored(stored) : MOVED_ON;
  }

  private RenderOutcome markFailed(RenderJob job, String reason) {
    return lifecycle.markFailed(job, reason) ? new RenderOutcome.Failed(reason) : MOVED_ON;
  }
}
