package com.shashank.invoicekit.invoice.flow;

import static com.shashank.invoicekit.invoice.flow.IntakeEvents.newEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.shashank.invoicekit.invoice.application.port.outbound.InvoiceStore;
import com.shashank.invoicekit.invoice.application.service.IntakeOutcome;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.support.SeriesReconciliation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Makes the final insert fail after a number was taken, as a lost connection or a full disk would.
 * The whole transaction must roll back, so the series keeps no gap and Kafka can redeliver the
 * event.
 */
@IntakeFlowTest
class IntakeFailureTest {

  private static final String FAILURE = "Simulated failure after numbering";

  @Autowired private IntakeService intake;
  @Autowired private InvoiceStore store;
  @Autowired private IntakeRows rows;
  @Autowired private SeriesReconciliation reconciliation;

  /** The store is a spy, so only its next save fails and later ones save normally. */
  @BeforeEach
  void failTheNextSave() {
    doThrow(new IllegalStateException(FAILURE))
        .doCallRealMethod()
        .when(store)
        .saveNumbered(any(), any());
  }

  @AfterEach
  void deleteRows() {
    rows.deleteAll();
  }

  @Test
  void nextEventGetsTheNumberTheFailedOneWouldHaveHad() {
    assertThatThrownBy(() -> intake.process(newEvent())).hasMessage(FAILURE);

    assertThat(intake.process(newEvent()))
        .isEqualTo(new IntakeOutcome.Created(IntakeEvents.number(1)));
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void failedEventIsLeftUnclaimedSoItsRedeliveryCreatesTheInvoice() {
    InvoiceRequested event = newEvent();
    assertThatThrownBy(() -> intake.process(event)).hasMessage(FAILURE);

    assertThat(rows.count("invoice_event")).isZero();
    assertThat(intake.process(event)).isEqualTo(new IntakeOutcome.Created(IntakeEvents.number(1)));
  }
}
