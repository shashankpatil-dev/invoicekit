package com.shashank.invoicekit.numbering.flow;

import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes one series so busy that orders keep failing with {@link SeriesBusyException}, then checks
 * that retrying, as Kafka redelivery would, still numbers every order exactly once without gaps.
 *
 * <p>Contention alone hits a 2ms lock timeout only sometimes, so one slow order holds the counter
 * while the others start, and every order arriving then must find the series busy. The tiny lock
 * timeout needs its own Spring context and database.
 */
@NumberingFlowTest
@TestPropertySource(properties = "invoicekit.numbering.lock-timeout=2ms")
class NumberingBusySeriesRetryTest {

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);
  private static final FinancialYear FINANCIAL_YEAR = FinancialYear.of(INVOICE_DATE);

  private static final int THREADS = 32;
  private static final int ORDERS_PER_THREAD = 10;
  private static final int ORDERS = THREADS * ORDERS_PER_THREAD;

  /** Enough tries that every order gets through, however often its series is busy. */
  private static final int MAX_TRIES = 1_000;

  /** Keeps the slow order's transaction running, counter locked, for 200 milliseconds. */
  private static final String HOLD_THE_COUNTER = "SELECT pg_sleep(0.2)";

  /** Longest the test waits for the slow order to lock the counter. */
  private static final long COUNTER_HELD_WITHIN_SECONDS = 10;

  @Autowired private DummyOrders dummyOrders;
  @Autowired private SeriesReconciliation reconciliation;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void deleteOrders() {
    dummyOrders.deleteAll();
  }

  @Test
  void ordersOnABusySeriesAreRetriedUntilEachHasExactlyOneNumber() throws Exception {
    ConcurrentOrders orders = new ConcurrentOrders(dummyOrders, MAX_TRIES);
    CompletableFuture<String> slowOrder = startSlowOrderHoldingTheCounter();

    List<String> numbers =
        orders.place(
            THREADS, ORDERS_PER_THREAD, () -> DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));

    List<String> expected = ONE_SHARD.firstNumbers(FINANCIAL_YEAR, ORDERS + 1);
    assertThat(orders.busyRetries()).isPositive();
    assertThat(slowOrder.join()).isEqualTo(expected.getFirst());
    assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected.subList(1, expected.size()));
    assertThat(dummyOrders.invoiceCount()).isEqualTo(ORDERS + 1);
    assertThat(reconciliation.problems()).isEmpty();
  }

  /**
   * Starts an order that keeps its transaction running for a moment after taking its number, like a
   * worker stalled mid-transaction, and returns once the counter is locked.
   */
  private CompletableFuture<String> startSlowOrderHoldingTheCounter() throws InterruptedException {
    CountDownLatch counterHeld = new CountDownLatch(1);
    CompletableFuture<String> slowOrder =
        CompletableFuture.supplyAsync(
            () ->
                transaction.execute(
                    status -> {
                      String number =
                          dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));
                      counterHeld.countDown();
                      jdbcClient.sql(HOLD_THE_COUNTER).query().listOfRows();
                      return number;
                    }));
    assertThat(counterHeld.await(COUNTER_HELD_WITHIN_SECONDS, SECONDS)).isTrue();
    return slowOrder;
  }
}
