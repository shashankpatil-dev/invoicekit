package com.shashank.invoicekit.invoice.adapter.kafka;

import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * The headers that tell a BU why its event was dead-lettered: the rejection code and every reason.
 * Spring Kafka adds the original topic, partition and offset next to them.
 *
 * <p>The listener container wraps the listener's exception, so the rejection is looked for along
 * the whole cause chain.
 */
public final class RejectionHeaders
    implements BiFunction<ConsumerRecord<?, ?>, Exception, Headers> {

  /** The rejection code, such as {@code VALIDATION_FAILED}. */
  public static final String ERROR_CODE = "x-error-code";

  /** Every reason the event was rejected, separated by {@code "; "}. */
  public static final String ERROR_MESSAGE = "x-error-message";

  private static final String REASON_SEPARATOR = "; ";

  /** Headers for the rejection behind the failure, or none when the failure was not a rejection. */
  @Override
  public Headers apply(ConsumerRecord<?, ?> message, Exception failure) {
    return rejectionBehind(failure).map(RejectionHeaders::headersFor).orElseGet(RecordHeaders::new);
  }

  private static Optional<RejectedEventException> rejectionBehind(Throwable failure) {
    return Stream.iterate(failure, cause -> cause != null, Throwable::getCause)
        .filter(RejectedEventException.class::isInstance)
        .map(RejectedEventException.class::cast)
        .findFirst();
  }

  private static Headers headersFor(RejectedEventException rejection) {
    return new RecordHeaders()
        .add(ERROR_CODE, utf8(rejection.code().name()))
        .add(ERROR_MESSAGE, utf8(String.join(REASON_SEPARATOR, rejection.reasons())));
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
