package com.shashank.invoicekit.numbering.flow;

import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ENTITY_CODE;
import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.FOUR_SHARDS;
import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.numbering.application.service.NumberingService;
import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.support.SeriesReconciliation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places dummy orders from many threads at once and checks every series stays unique and gap-free,
 * covering the concurrency, duplicate and rollback tests in docs/NUMBERING.md section 14.
 */
@NumberingFlowTest
class NumberingConcurrencyTest {

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);
  private static final FinancialYear FINANCIAL_YEAR = FinancialYear.of(INVOICE_DATE);

  private static final int THREADS = 32;
  private static final int ORDERS_PER_THREAD = 50;
  private static final int ORDERS = THREADS * ORDERS_PER_THREAD;
  private static final int DUPLICATE_SENDERS = 16;

  @Autowired private DummyOrders dummyOrders;
  @Autowired private SeriesReconciliation reconciliation;
  @Autowired private NumberingService numberingService;
  @Autowired private TransactionTemplate transaction;

  @AfterEach
  void deleteOrders() {
    dummyOrders.deleteAll();
  }

  @Test
  void parallelOrdersOnOneShardGetEveryNumberFromOneToTheTotalOnce() throws Exception {
    List<String> numbers =
        new ConcurrentOrders(dummyOrders)
            .place(THREADS, ORDERS_PER_THREAD, () -> DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));

    assertThat(numbers)
        .containsExactlyInAnyOrderElementsOf(
            LongStream.rangeClosed(1, ORDERS).mapToObj("SUBA26%010d"::formatted).toList());
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void parallelOrdersOnFourShardsKeepEveryShardGapFree() throws Exception {
    List<String> numbers =
        new ConcurrentOrders(dummyOrders)
            .place(
                THREADS, ORDERS_PER_THREAD, () -> DummyOrder.newOrder(FOUR_SHARDS, INVOICE_DATE));

    Map<String, Long> lastNumbers =
        reconciliation.lastNumbersByShard(ENTITY_CODE, FOUR_SHARDS.code(), FINANCIAL_YEAR);
    assertThat(numbers).hasSize(ORDERS).doesNotHaveDuplicates();
    assertThat(lastNumbers).containsOnlyKeys("A", "B", "C", "D");
    assertThat(reconciliation.issuedCount(ENTITY_CODE, FOUR_SHARDS.code(), FINANCIAL_YEAR))
        .isEqualTo(ORDERS);
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void sameEventFromManyThreadsCreatesOneInvoice() throws Exception {
    DummyOrder order = DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE);

    List<String> numbers =
        new ConcurrentOrders(dummyOrders).place(DUPLICATE_SENDERS, 1, () -> order);

    assertThat(numbers).hasSize(DUPLICATE_SENDERS).containsOnly("SUBA260000000001");
    assertThat(dummyOrders.invoiceCount()).isEqualTo(1);
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, ONE_SHARD.code(), FINANCIAL_YEAR))
        .isEqualTo(Map.of("A", 1L));
  }

  @Test
  void orderFailingAfterNumberingLeavesNoGapForTheNextOrder() {
    AtomicReference<String> failedNumber = new AtomicReference<>();

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      failedNumber.set(
                          dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE)));
                      throw new IllegalStateException("Simulated failure after numbering");
                    }))
        .hasMessage("Simulated failure after numbering");
    String nextNumber = dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));

    assertThat(nextNumber).isEqualTo(failedNumber.get()).isEqualTo("SUBA260000000001");
    assertThat(dummyOrders.invoiceCount()).isEqualTo(1);
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void numberingOutsideATransactionIsRefusedAndTouchesNoCounter() {
    DummyOrder order = DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE);

    assertThatThrownBy(() -> numberingService.assign(order.numberingRequest()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, ONE_SHARD.code(), FINANCIAL_YEAR))
        .isEmpty();
  }
}
