package com.shashank.invoicekit.invoice.flow;

import static com.shashank.invoicekit.invoice.flow.IntakeEvents.BUSINESS_UNIT;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.ENTITY_CODE;
import static com.shashank.invoicekit.invoice.flow.IntakeEvents.FINANCIAL_YEAR;
import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.invoice.application.service.IntakeOutcome;
import com.shashank.invoicekit.invoice.application.service.IntakeService;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.support.SeriesReconciliation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sends a burst of events from many threads at once, some of them delivered twice at the same
 * moment, like a busy consumer group after a rebalance. Each event must still get exactly one
 * invoice, and the series must stay gap-free.
 */
@IntakeFlowTest
class IntakeConcurrencyTest {

  private static final int THREADS = 32;
  private static final int EVENTS_PER_THREAD = 20;
  private static final int SENDS = THREADS * EVENTS_PER_THREAD;

  /**
   * Fewer event IDs than sends, so IDs wrap around: thread 24 sends the same events as thread 0, in
   * the same order and at the same time, and so on up to thread 31.
   */
  private static final int DISTINCT_EVENTS = 480;

  /** Tries per event while its series is busy, as Kafka would redeliver it. */
  private static final int MAX_TRIES = 5;

  @Autowired private IntakeService intake;
  @Autowired private IntakeRows rows;
  @Autowired private SeriesReconciliation reconciliation;

  @AfterEach
  void deleteRows() {
    rows.deleteAll();
  }

  @Test
  void parallelEventsWithRepeatsGetOneInvoiceEachFromAGapFreeSeries() throws Exception {
    List<IntakeOutcome> outcomes = sendFromAllThreads();

    assertThat(outcomes)
        .filteredOn(IntakeOutcome.Duplicate.class::isInstance)
        .hasSize(SENDS - DISTINCT_EVENTS);
    assertThat(createdNumbers(outcomes))
        .containsExactlyInAnyOrderElementsOf(
            LongStream.rangeClosed(1, DISTINCT_EVENTS).mapToObj(IntakeEvents::number).toList());
    assertThat(rows.count("invoice")).isEqualTo(DISTINCT_EVENTS);
    assertThat(rows.invoicedEvents()).isEqualTo(DISTINCT_EVENTS);
    assertThat(rows.count("invoice_event")).isEqualTo(DISTINCT_EVENTS);
    assertThat(rows.count("invoice_audit")).isEqualTo(DISTINCT_EVENTS);
    assertThat(rows.count("outbox")).isEqualTo(DISTINCT_EVENTS);
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, BUSINESS_UNIT, FINANCIAL_YEAR))
        .isEqualTo(Map.of("A", (long) DISTINCT_EVENTS));
    assertThat(reconciliation.problems()).isEmpty();
  }

  /** Starts every thread at the same moment and collects every outcome. */
  private List<IntakeOutcome> sendFromAllThreads() throws Exception {
    CyclicBarrier allReady = new CyclicBarrier(THREADS);
    List<Callable<List<IntakeOutcome>>> senders =
        IntStream.range(0, THREADS)
            .<Callable<List<IntakeOutcome>>>mapToObj(
                thread ->
                    () -> {
                      allReady.await();
                      return sendInTurn(thread);
                    })
            .toList();
    List<IntakeOutcome> outcomes = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
      for (Future<List<IntakeOutcome>> sent : executor.invokeAll(senders)) {
        outcomes.addAll(sent.get());
      }
    }
    return outcomes;
  }

  private List<IntakeOutcome> sendInTurn(int thread) {
    return IntStream.range(0, EVENTS_PER_THREAD)
        .mapToObj(index -> IntakeEvents.event(eventId(thread, index)))
        .map(this::processRetryingBusy)
        .toList();
  }

  private static String eventId(int thread, int index) {
    return "concurrent-" + (thread * EVENTS_PER_THREAD + index) % DISTINCT_EVENTS;
  }

  /** A busy series rolls the whole transaction back, so trying again is safe. */
  private IntakeOutcome processRetryingBusy(InvoiceRequested event) {
    for (int attempt = 1; ; attempt++) {
      try {
        return intake.process(event);
      } catch (SeriesBusyException busy) {
        if (attempt == MAX_TRIES) {
          throw busy;
        }
      }
    }
  }

  private static List<String> createdNumbers(List<IntakeOutcome> outcomes) {
    return outcomes.stream()
        .filter(IntakeOutcome.Created.class::isInstance)
        .map(IntakeOutcome.Created.class::cast)
        .map(IntakeOutcome.Created::invoiceNumber)
        .toList();
  }
}
