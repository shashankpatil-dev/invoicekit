package com.shashank.invoicekit.numbering.application.port.outbound;

import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;

/**
 * Hands out the next number in a series.
 *
 * <p>It must run inside the caller's transaction, so the number commits or rolls back together
 * with the invoice that uses it. A rolled-back number is never lost, so series stay gap-free.
 */
public interface SequenceCounter {

  /**
   * Returns the next sequence number for the series, starting at 1.
   *
   * @param series the series to count in
   * @param maxSequence the largest number the series' format can hold
   * @throws SeriesExhaustedException if the series already holds {@code maxSequence} numbers
   * @throws SeriesBusyException if the counter stays locked longer than the configured timeout
   */
  long next(SeriesKey series, long maxSequence);
}
