package com.shashank.invoicekit.outbox.application.port.outbound;

import com.shashank.invoicekit.outbox.domain.entity.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reads and updates the outbox table for the publisher and its cleanup. */
public interface OutboxRepository {

  /**
   * Claims the oldest unsent rows for the caller's transaction. Rows another transaction already
   * holds are skipped, so several publishers never claim the same row.
   *
   * @param batchSize the most rows to claim
   */
  List<OutboxMessage> claimUnsent(int batchSize);

  /** Marks these rows sent, so no publisher claims them again. */
  void markSent(List<UUID> ids);

  /**
   * Deletes one batch of rows sent before the cutoff. Unsent rows are never deleted.
   *
   * @param batchSize the most rows to delete
   * @return how many rows were deleted
   */
  int deleteSentBefore(Instant cutoff, int batchSize);
}
