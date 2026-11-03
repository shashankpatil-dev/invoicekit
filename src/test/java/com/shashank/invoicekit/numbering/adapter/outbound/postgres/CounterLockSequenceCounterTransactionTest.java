package com.shashank.invoicekit.numbering.adapter.outbound.postgres;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.numbering.domain.entity.SeriesKey;
import com.shashank.invoicekit.numbering.domain.entity.Shard;
import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.support.PostgresFixture;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the counter across real, separate transactions: rollbacks leave no gap, and a held counter
 * makes other callers fail fast instead of waiting.
 *
 * <p>Tests here commit, so they run outside the usual test transaction and delete their counters
 * afterwards.
 */
@JdbcTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testseries")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(PostgresFixture.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CounterLockSequenceCounterTransactionTest {

  private static final String ENTITY_CODE = "TEST_NUMBERING";
  private static final SeriesKey SERIES =
      new SeriesKey(ENTITY_CODE, "numbering-test", new FinancialYear(2026), new Shard(0));
  private static final long MAX_SEQUENCE = 9_999_999_999L;

  private static final Duration SHORT = Duration.ofMillis(200);
  private static final Duration LONG = Duration.ofSeconds(10);

  private static final String LOCK_TIMEOUT_STATE = "55P03";
  private static final String STATEMENT_TIMEOUT_STATE = "57014";

  /** Longest the test waits for the other transaction to take the counter lock. */
  private static final long LOCK_TAKEN_WITHIN_SECONDS = 10;

  @Autowired private JdbcClient jdbcClient;
  @Autowired private TransactionTemplate transaction;

  @AfterEach
  void deleteCounters() {
    jdbcClient.sql("DELETE FROM invoice_counter WHERE entity_code = ?").param(ENTITY_CODE).update();
  }

  @Test
  void rolledBackNumberGoesToTheNextTransaction() {
    CounterLockSequenceCounter counter = counter(LONG, LONG);
    transaction.execute(status -> counter.next(SERIES, MAX_SEQUENCE));

    Long rolledBack =
        transaction.execute(
            status -> {
              status.setRollbackOnly();
              return counter.next(SERIES, MAX_SEQUENCE);
            });
    Long committed = transaction.execute(status -> counter.next(SERIES, MAX_SEQUENCE));

    assertThat(rolledBack).isEqualTo(2);
    assertThat(committed).isEqualTo(rolledBack);
  }

  @Test
  void heldCounterMakesTheNextCallerBusyAfterTheLockTimeout() throws Exception {
    whileAnotherTransactionHoldsTheCounter(
        () -> assertBusy(counter(SHORT, LONG), LOCK_TIMEOUT_STATE));
  }

  @Test
  void heldCounterMakesTheNextCallerBusyAfterTheStatementTimeout() throws Exception {
    whileAnotherTransactionHoldsTheCounter(
        () -> assertBusy(counter(LONG, SHORT), STATEMENT_TIMEOUT_STATE));
  }

  /** Checks the caller gives up as busy, for the reason PostgreSQL reported. */
  private void assertBusy(CounterLockSequenceCounter impatient, String sqlState) {
    assertThatThrownBy(() -> transaction.execute(status -> impatient.next(SERIES, MAX_SEQUENCE)))
        .isInstanceOf(SeriesBusyException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo(sqlState));
  }

  /**
   * Takes the counter lock in a second transaction on its own thread, runs the check, then lets
   * that transaction commit. The lock is always released, even when the check fails.
   */
  private void whileAnotherTransactionHoldsTheCounter(Runnable check) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CompletableFuture<Void> release = new CompletableFuture<>();
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> holder =
          executor.submit(
              () ->
                  transaction.execute(
                      status -> {
                        counter(LONG, LONG).next(SERIES, MAX_SEQUENCE);
                        locked.countDown();
                        return release.join();
                      }));
      try {
        assertThat(locked.await(LOCK_TAKEN_WITHIN_SECONDS, SECONDS)).isTrue();
        check.run();
      } finally {
        release.complete(null);
      }
      holder.get();
    }
  }

  private CounterLockSequenceCounter counter(Duration lockTimeout, Duration statementTimeout) {
    return new CounterLockSequenceCounter(jdbcClient, lockTimeout, statementTimeout);
  }
}
