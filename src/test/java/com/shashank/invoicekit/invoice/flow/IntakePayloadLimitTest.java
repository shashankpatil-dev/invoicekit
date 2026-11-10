package com.shashank.invoicekit.invoice.flow;

import static com.shashank.invoicekit.invoice.flow.IntakeEvents.newEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Checks a payload over the size limit is rejected before numbering. The limit is set far below the
 * example's payload, which needs its own Spring context and database.
 */
@IntakeFlowTest
@TestPropertySource(properties = "invoicekit.intake.max-payload-bytes=1000")
class IntakePayloadLimitTest {

  @Autowired private IntakeService intake;
  @Autowired private IntakeRows rows;

  @Test
  void payloadOverTheLimitFailsValidationWithoutANumber() {
    assertThatThrownBy(() -> intake.process(newEvent()))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
              assertThat(rejected.reasons())
                  .singleElement()
                  .asString()
                  .startsWith("payload is ")
                  .endsWith(" bytes, over the limit of 1000");
            });
    assertThat(rows.count("invoice_counter")).isZero();
    assertThat(rows.count("invoice_event")).isZero();
  }
}
