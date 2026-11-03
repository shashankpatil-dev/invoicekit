package com.shashank.invoicekit.numbering.flow;

import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD;
import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs far more threads than pooled connections. Threads must wait for a free connection instead of
 * failing, and every order still gets a number on its first try.
 *
 * <p>The small pool needs its own Spring context and database.
 */
@NumberingFlowTest
@TestPropertySource(
    properties = "spring.datasource.hikari.maximum-pool-size=" + NumberingSmallPoolTest.POOL_SIZE)
class NumberingSmallPoolTest {

  static final int POOL_SIZE = 4;

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);
  private static final FinancialYear FINANCIAL_YEAR = FinancialYear.of(INVOICE_DATE);

  private static final int THREADS = 32;
  private static final int ORDERS_PER_THREAD = 20;
  private static final int ORDERS = THREADS * ORDERS_PER_THREAD;

  /** No retries, so any order that fails instead of waiting fails the test. */
  private static final int SINGLE_TRY = 1;

  @Autowired private DummyOrders dummyOrders;
  @Autowired private SeriesReconciliation reconciliation;
  @Autowired private HikariDataSource dataSource;

  @AfterEach
  void deleteOrders() {
    dummyOrders.deleteAll();
  }

  @Test
  void moreThreadsThanConnectionsWaitTheirTurnAndEveryOrderIsNumbered() throws Exception {
    List<String> numbers =
        new ConcurrentOrders(dummyOrders, SINGLE_TRY)
            .place(THREADS, ORDERS_PER_THREAD, () -> DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));

    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(POOL_SIZE);
    assertThat(numbers)
        .containsExactlyInAnyOrderElementsOf(ONE_SHARD.firstNumbers(FINANCIAL_YEAR, ORDERS));
    assertThat(reconciliation.problems()).isEmpty();
  }
}
