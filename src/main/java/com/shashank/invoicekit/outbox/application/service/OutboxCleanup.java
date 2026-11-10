package com.shashank.invoicekit.outbox.application.service;

import com.shashank.invoicekit.outbox.application.port.outbound.OutboxRepository;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes sent outbox rows once they are older than the retention, so the table stays small while
 * recent rows remain for investigation. Each batch is its own short statement, so cleanup never
 * holds many locks at once.
 */
public class OutboxCleanup {

  private final OutboxRepository repository;
  private final Duration retention;
  private final int batchSize;

  /**
   * @param retention how long sent rows are kept
   * @param batchSize the most rows one delete statement removes
   */
  public OutboxCleanup(OutboxRepository repository, Duration retention, int batchSize) {
    this.repository = repository;
    this.retention = retention;
    this.batchSize = batchSize;
  }

  /**
   * Deletes batch after batch until a batch comes back short, meaning no expired rows are left.
   *
   * @return how many rows were deleted in total
   */
  public int deleteExpired() {
    Instant cutoff = Instant.now().minus(retention);
    int total = 0;
    int deleted;
    do {
      deleted = repository.deleteSentBefore(cutoff, batchSize);
      total += deleted;
    } while (deleted == batchSize);
    return total;
  }
}
