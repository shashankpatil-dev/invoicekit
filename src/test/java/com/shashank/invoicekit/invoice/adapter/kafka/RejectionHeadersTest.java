package com.shashank.invoicekit.invoice.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;

/** Checks the dead-letter headers that explain a rejection. */
class RejectionHeadersTest {

  private static final ConsumerRecord<String, String> MESSAGE =
      new ConsumerRecord<>("invoice.requested", 0, 0, "subscriptions:sub-1", "{}");

  private final RejectionHeaders rejectionHeaders = new RejectionHeaders();

  @Test
  void rejectionWrappedByTheContainerGivesItsCodeAndEveryReason() {
    RejectedEventException rejection =
        new RejectedEventException(
            RejectionCode.VALIDATION_FAILED, List.of("currency is required", "bu is required"));

    Headers headers =
        rejectionHeaders.apply(MESSAGE, new RuntimeException("listener failed", rejection));

    assertThat(text(headers, RejectionHeaders.ERROR_CODE)).isEqualTo("VALIDATION_FAILED");
    assertThat(text(headers, RejectionHeaders.ERROR_MESSAGE))
        .isEqualTo("currency is required; bu is required");
  }

  @Test
  void failureThatIsNotARejectionAddsNoHeaders() {
    Headers headers = rejectionHeaders.apply(MESSAGE, new IllegalStateException("database down"));

    assertThat(headers.toArray()).isEmpty();
  }

  private static String text(Headers headers, String name) {
    return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
  }
}
