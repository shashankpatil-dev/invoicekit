package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceStore;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import com.shashank.invoicekit.numbering.application.service.NumberingRequest;
import com.shashank.invoicekit.numbering.application.service.NumberingService;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one database transaction of intake: claim the event, take a number, save the invoice. Either
 * all of it commits or none of it does, so a failure never leaves a gap in a series or a claimed
 * event without its invoice. See docs/INTAKE.md section 7.
 *
 * <p>Everything that can be prepared is prepared before this runs, so the counter row stays locked
 * only for the final insert and the commit.
 */
public class IntakeTransaction {

  private final InvoiceStore store;
  private final NumberingService numberingService;

  public IntakeTransaction(InvoiceStore store, NumberingService numberingService) {
    this.store = store;
    this.numberingService = numberingService;
  }

  /**
   * Saves the invoice under the next number, unless an earlier delivery already claimed its event.
   *
   * @param invoice the assembled payload, still without its number
   * @param numbering which series the number comes from
   * @throws RejectedEventException with {@code SERIES_EXHAUSTED} when the series is full
   * @throws SeriesBusyException when the series stays locked too long, so the event is retried
   */
  @Transactional
  public IntakeOutcome commit(InvoicePayload invoice, NumberingRequest numbering) {
    if (!store.claimEvent(
        invoice.businessUnit(),
        invoice.sourceEventId(),
        invoice.invoiceId(),
        invoice.financialYear())) {
      return new IntakeOutcome.Duplicate();
    }
    InvoiceNumber number = assign(numbering);
    store.saveNumbered(invoice.withInvoiceNumber(number.value()), number);
    return new IntakeOutcome.Created(number.value());
  }

  /** A full series can't be fixed by retrying, so its event goes to the dead-letter topic. */
  private InvoiceNumber assign(NumberingRequest numbering) {
    try {
      return numberingService.assign(numbering);
    } catch (SeriesExhaustedException full) {
      throw new RejectedEventException(RejectionCode.SERIES_EXHAUSTED, List.of(full.getMessage()));
    }
  }
}
