package com.shashank.invoicekit.numbering.flow;

import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A worker that freezes while holding a series' counter lock. PostgreSQL ends its session after the
 * idle-in-transaction timeout, so a worker waiting behind it gets the number it never committed, as
 * docs/NUMBERING.md section 8 describes.
 *
 * <p>The short timeout needs its own Spring context and database.
 */
@NumberingFlowTest
@TestPropertySource(
    properties = {
      "invoicekit.database.idle-in-transaction-timeout=1s",
      "invoicekit.numbering.lock-timeout=5s"
    })
class NumberingFrozenWorkerTest {

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);

  /** Longest the test waits for the frozen worker to take its number. */
  private static final long NUMBERED_WITHIN_SECONDS = 10;

  @Autowired private DummyOrders dummyOrders;
  @Autowired private SeriesReconciliation reconciliation;
  @Autowired private TransactionTemplate transaction;

  @AfterEach
  void deleteOrders() {
    dummyOrders.deleteAll();
  }

  @Test
  void frozenWorkersSessionIsEndedAndTheWaitingWorkerReusesItsNumber() throws Exception {
    AtomicReference<String> frozenNumber = new AtomicReference<>();
    CountDownLatch numbered = new CountDownLatch(1);
    CompletableFuture<Void> wakeUp = new CompletableFuture<>();
    String waitingNumber;
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> frozenWorker =
          executor.submit(() -> placeThenFreeze(frozenNumber, numbered, wakeUp));
      try {
        assertThat(numbered.await(NUMBERED_WITHIN_SECONDS, SECONDS)).isTrue();
        // Waits on the counter lock, which only PostgreSQL ending the frozen session can release.
        waitingNumber = dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));
      } finally {
        wakeUp.complete(null);
      }
      assertThatThrownBy(frozenWorker::get).hasCauseInstanceOf(TransactionSystemException.class);
    }

    assertThat(waitingNumber).isEqualTo(frozenNumber.get()).isEqualTo("SUBA260000000001");
    assertThat(dummyOrders.invoiceCount()).isEqualTo(1);
    assertThat(reconciliation.problems()).isEmpty();
  }

  /**
   * Places an order in a transaction that then sits idle, holding the counter lock, until told to
   * wake up and commit.
   */
  private void placeThenFreeze(
      AtomicReference<String> number, CountDownLatch numbered, CompletableFuture<Void> wakeUp) {
    transaction.executeWithoutResult(
        status -> {
          number.set(dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE)));
          numbered.countDown();
          wakeUp.join();
        });
  }
}
