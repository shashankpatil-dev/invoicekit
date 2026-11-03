package com.shashank.invoicekit.numbering.flow;

import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD;
import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.TWO_DIGIT_SEQUENCE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Breaks things in the middle of numbering, the way production can: a connection killed before
 * commit, the database paused under load, and a series that runs out. Each must leave every series
 * gap-free, as docs/NUMBERING.md section 8 promises.
 */
@NumberingFlowTest
class NumberingFailureTest {

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);
  private static final FinancialYear FINANCIAL_YEAR = FinancialYear.of(INVOICE_DATE);

  /** Ends a session, waiting up to 5 seconds until it has gone, so its next statement fails. */
  private static final String TERMINATE_SESSION = "SELECT pg_terminate_backend(?, 5000)";

  private static final int THREADS = 32;
  private static final int ORDERS_PER_THREAD = 20;
  private static final int ORDERS = THREADS * ORDERS_PER_THREAD;

  /** Orders started before the pause, so it lands while the load is running. */
  private static final int ORDERS_BEFORE_PAUSE = 2 * THREADS;

  /** Longest the test waits for the load to get going before pausing. */
  private static final long LOAD_RUNNING_WITHIN_SECONDS = 10;

  private static final Duration PAUSE = Duration.ofSeconds(3);

  /** Every number a two-digit sequence can hold. */
  private static final int TWO_DIGIT_CAPACITY = 99;

  @Autowired private DummyOrders dummyOrders;
  @Autowired private SeriesReconciliation reconciliation;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MeterRegistry registry;
  @Autowired private PostgreSQLContainer<?> postgres;

  @AfterEach
  void deleteOrders() {
    dummyOrders.deleteAll();
  }

  @Test
  void orderWhoseConnectionIsKilledBeforeCommitLeavesItsNumberForTheNextOrder() {
    AtomicReference<String> killedNumber = new AtomicReference<>();

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      killedNumber.set(
                          dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE)));
                      terminateFromAnotherConnection(currentSessionId());
                    }))
        .isInstanceOf(TransactionSystemException.class);
    assertThat(dummyOrders.invoiceCount()).isZero();
    String nextNumber = dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));

    assertThat(nextNumber).isEqualTo(killedNumber.get()).isEqualTo("SUBA260000000001");
    assertThat(dummyOrders.invoiceCount()).isEqualTo(1);
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void ordersInFlightWhileTheDatabaseIsPausedEachEndUpWithOneNumber() throws Exception {
    CountDownLatch loadRunning = new CountDownLatch(ORDERS_BEFORE_PAUSE);
    List<String> numbers;
    try (ExecutorService loader = Executors.newSingleThreadExecutor()) {
      Future<List<String>> placed = loader.submit(() -> placeLoadSignalling(loadRunning));
      assertThat(loadRunning.await(LOAD_RUNNING_WITHIN_SECONDS, SECONDS)).isTrue();
      pauseDatabase(PAUSE);
      numbers = placed.get();
    }

    assertThat(numbers)
        .containsExactlyInAnyOrderElementsOf(ONE_SHARD.firstNumbers(FINANCIAL_YEAR, ORDERS));
    assertThat(dummyOrders.invoiceCount()).isEqualTo(ORDERS);
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void orderAfterTheLastNumberIsRefusedAndRolledBackWithoutMovingTheCounter() {
    double exhaustedBefore = exhaustedCount();
    List<String> numbers =
        Stream.generate(() -> DummyOrder.newOrder(TWO_DIGIT_SEQUENCE, INVOICE_DATE))
            .limit(TWO_DIGIT_CAPACITY)
            .map(dummyOrders::place)
            .toList();

    assertThatThrownBy(
            () -> dummyOrders.place(DummyOrder.newOrder(TWO_DIGIT_SEQUENCE, INVOICE_DATE)))
        .isInstanceOf(SeriesExhaustedException.class);
    assertThat(numbers)
        .containsExactlyElementsOf(
            TWO_DIGIT_SEQUENCE.firstNumbers(FINANCIAL_YEAR, TWO_DIGIT_CAPACITY))
        .last()
        .isEqualTo("TSTA2699");
    assertThat(dummyOrders.invoiceCount()).isEqualTo(TWO_DIGIT_CAPACITY);
    assertThat(reconciliation.issuedCount(TWO_DIGIT_SEQUENCE, FINANCIAL_YEAR))
        .isEqualTo(TWO_DIGIT_CAPACITY);
    assertThat(exhaustedCount() - exhaustedBefore).isEqualTo(1);
    assertThat(reconciliation.problems()).isEmpty();
  }

  /**
   * Places the load from many threads, counting down {@code ordersStarted} as each order starts.
   */
  private List<String> placeLoadSignalling(CountDownLatch ordersStarted) throws Exception {
    return new ConcurrentOrders(dummyOrders)
        .place(
            THREADS,
            ORDERS_PER_THREAD,
            () -> {
              ordersStarted.countDown();
              return DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE);
            });
  }

  /** The database session behind the current transaction's connection. */
  private int currentSessionId() {
    return jdbcClient.sql("SELECT pg_backend_pid()").query(Integer.class).single();
  }

  /**
   * Ends a session as an operator or a dropped network would. A new thread has no transaction, so
   * the call borrows a different pooled connection from the one being killed.
   */
  private void terminateFromAnotherConnection(int sessionId) {
    boolean terminated =
        CompletableFuture.supplyAsync(
                () ->
                    jdbcClient
                        .sql(TERMINATE_SESSION)
                        .param(sessionId)
                        .query(Boolean.class)
                        .single())
            .join();
    assertThat(terminated).isTrue();
  }

  /** Freezes the test database's container, then always thaws it, even if the pause failed. */
  private void pauseDatabase(Duration pause) throws InterruptedException {
    DockerClient docker = DockerClientFactory.instance().client();
    String containerId = postgres.getContainerId();
    docker.pauseContainerCmd(containerId).exec();
    try {
      Thread.sleep(pause);
    } finally {
      docker.unpauseContainerCmd(containerId).exec();
    }
  }

  private double exhaustedCount() {
    return registry
        .counter(
            "numbering.exhausted",
            "businessUnit",
            TWO_DIGIT_SEQUENCE.code(),
            "entity",
            TestBusinessUnit.ENTITY_CODE)
        .count();
  }
}
