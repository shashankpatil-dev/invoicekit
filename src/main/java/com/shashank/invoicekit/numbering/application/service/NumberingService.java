package com.shashank.invoicekit.numbering.application.service;

import com.shashank.invoicekit.numbering.application.port.outbound.SequenceCounter;
import com.shashank.invoicekit.numbering.domain.entity.InvoiceNumber;
import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives an invoice the next number in its series.
 *
 * <p>It only runs inside the caller's transaction, so a number always commits or rolls back
 * together with the invoice that uses it, and series stay gap-free.
 */
public class NumberingService {

  private final SequenceCounter sequenceCounter;
  private final NumberingMetrics metrics;

  public NumberingService(SequenceCounter sequenceCounter, NumberingMetrics metrics) {
    this.sequenceCounter = sequenceCounter;
    this.metrics = metrics;
  }

  /**
   * Takes the next number in the invoice's series and formats it.
   *
   * @throws SeriesExhaustedException if the series has no numbers left
   * @throws SeriesBusyException if the series stayed locked too long
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public InvoiceNumber assign(NumberingRequest request) {
    SeriesKey series = request.series();
    NumberFormat format = request.rule().format();
    long sequence = nextSequence(series, format.maxSequence());
    return new InvoiceNumber(
        series, sequence, format.format(series.shard(), series.financialYear(), sequence));
  }

  private long nextSequence(SeriesKey series, long maxSequence) {
    long sequence =
        metrics.measureAllocation(series, () -> sequenceCounter.next(series, maxSequence));
    metrics.recordAllocation(series, sequence, maxSequence);
    return sequence;
  }
}
