package com.shashank.invoicekit.outbox.adapter.inbound;

import com.shashank.invoicekit.outbox.application.service.OutboxCleanup;
import com.shashank.invoicekit.outbox.application.service.OutboxRelay;
import java.time.Duration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the outbox publisher and its cleanup on fixed delays, in the {@code jobs} profile. Each run
 * starts the delay after the previous run ends, so runs never overlap within one pod.
 */
public class OutboxSchedule implements SchedulingConfigurer {

  private final OutboxRelay relay;
  private final OutboxCleanup cleanup;
  private final int batchSize;
  private final Duration pollInterval;
  private final Duration cleanupInterval;

  /**
   * @param batchSize the relay's batch size, so a full batch can be recognised
   * @param pollInterval the wait between publishing runs
   * @param cleanupInterval the wait between cleanup runs
   */
  public OutboxSchedule(
      OutboxRelay relay,
      OutboxCleanup cleanup,
      int batchSize,
      Duration pollInterval,
      Duration cleanupInterval) {
    this.relay = relay;
    this.cleanup = cleanup;
    this.batchSize = batchSize;
    this.pollInterval = pollInterval;
    this.cleanupInterval = cleanupInterval;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(this::publishBacklog, pollInterval);
    registrar.addFixedDelayTask(cleanup::deleteExpired, cleanupInterval);
  }

  /**
   * Publishes batch after batch while each one comes back full, so a backlog drains without waiting
   * for the next poll. A short batch means the outbox is caught up, or some sends failed and wait
   * for the next poll.
   */
  private void publishBacklog() {
    int sent;
    do {
      sent = relay.publishBatch();
    } while (sent == batchSize);
  }
}
