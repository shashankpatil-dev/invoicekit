package com.shashank.invoicekit.invoice.adapter.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.RetryListener;

/**
 * Logs every attempt intake could not complete, keyed by the event's Kafka key. Spring Kafka itself
 * logs a failed attempt without its cause, so without this an outage, or a bug that fails every
 * attempt, would hold a partition with nothing in the log saying why.
 *
 * <p>Each failure gets one line with its root cause, and a rejected event a second line once it is
 * on the dead-letter topic. Stack traces are left out, so a burst of bad events stays readable.
 */
public final class FailedEventLog implements RetryListener {

  private static final Logger log = LoggerFactory.getLogger(FailedEventLog.class);

  /** Any failed attempt. A passing failure is retried, and a rejection goes on to the DLQ. */
  @Override
  public void failedDelivery(ConsumerRecord<?, ?> message, Exception failure, int attempt) {
    log.warn(
        "Event {} failed on attempt {}: {}", message.key(), attempt, rootCause(failure).toString());
  }

  /** A rejected event, now on the dead-letter topic with its reasons. */
  @Override
  public void recovered(ConsumerRecord<?, ?> message, Exception failure) {
    log.warn(
        "Event {} was sent to the dead-letter topic: {}",
        message.key(),
        rootCause(failure).getMessage());
  }

  private static Throwable rootCause(Exception failure) {
    return NestedExceptionUtils.getMostSpecificCause(failure);
  }
}
