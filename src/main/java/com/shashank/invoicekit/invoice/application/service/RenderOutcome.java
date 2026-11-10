package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.domain.entity.StoredDocument;
import org.slf4j.event.Level;

/**
 * What rendering did with one invoice. Every outcome is final, so the message can be acked. Only
 * failures that can be retried are thrown instead.
 */
public sealed interface RenderOutcome {

  /** What happened, in words for the render log, such as "was skipped, because it is STORED". */
  String summary();

  /** How loudly the outcome is logged. Most are routine. */
  default Level logLevel() {
    return Level.INFO;
  }

  /** The PDF is stored and the invoice is now {@code STORED}. */
  record Stored(StoredDocument document) implements RenderOutcome {

    @Override
    public String summary() {
      return "was stored at " + document.uri();
    }
  }

  /** Nothing changed, because the invoice was missing or already past {@code NUMBERED}. */
  record Skipped(String reason) implements RenderOutcome {

    @Override
    public String summary() {
      return "was skipped, because " + reason;
    }
  }

  /** The document engine refused the invoice, which is now {@code FAILED}. */
  record Failed(String reason) implements RenderOutcome {

    @Override
    public String summary() {
      return "was marked FAILED, because the engine refused it: " + reason;
    }

    /** An operator has to fix the template or payload, so this one stands out. */
    @Override
    public Level logLevel() {
      return Level.WARN;
    }
  }
}
