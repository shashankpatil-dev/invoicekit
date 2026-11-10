package com.shashank.invoicekit.invoice.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks that a rejection carries its code and every reason. */
class RejectedEventExceptionTest {

  @Test
  void messageNamesTheCodeAndEveryReason() {
    RejectedEventException rejected =
        new RejectedEventException(
            RejectionCode.VALIDATION_FAILED, List.of("bu is required", "currency is required"));

    assertThat(rejected)
        .hasMessage("VALIDATION_FAILED: bu is required; currency is required")
        .isInstanceOf(RuntimeException.class);
    assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
    assertThat(rejected.reasons()).containsExactly("bu is required", "currency is required");
  }

  @Test
  void reasonsCannotChangeAfterwards() {
    List<String> reasons = new ArrayList<>(List.of("bu is required"));
    RejectedEventException rejected = new RejectedEventException(RejectionCode.UNKNOWN_BU, reasons);

    reasons.add("later");

    assertThat(rejected.reasons()).containsExactly("bu is required");
  }
}
