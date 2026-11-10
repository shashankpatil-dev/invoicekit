package com.shashank.invoicekit.numbering.flow;

import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ENTITY_CODE;
import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.FOUR_SHARDS;
import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD;
import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD_RESHARDED_TO_FOUR;
import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.numbering.domain.entity.FinancialYear;
import com.shashank.invoicekit.support.SeriesReconciliation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Unusual but normal order streams that must not disturb numbering: an event delivered again after
 * it committed, a business unit changing its shard count, the financial year turning over under
 * load, and two business units busy at the same time.
 */
@NumberingFlowTest
class NumberingEdgeCaseTest {

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);
  private static final FinancialYear FINANCIAL_YEAR = FinancialYear.of(INVOICE_DATE);

  private static final LocalDate LAST_DAY_OF_YEAR = LocalDate.of(2027, 3, 31);
  private static final LocalDate FIRST_DAY_OF_NEXT_YEAR = LocalDate.of(2027, 4, 1);

  private static final int THREADS = 32;
  private static final int ORDERS_PER_THREAD = 20;
  private static final int ORDERS = THREADS * ORDERS_PER_THREAD;

  private static final int ORDERS_BEFORE_RESHARD = 10;

  /** Enough new events that each of the four shards is sure to get some. */
  private static final int ORDERS_AFTER_RESHARD = 100;

  @Autowired private DummyOrders dummyOrders;
  @Autowired private SeriesReconciliation reconciliation;

  @AfterEach
  void deleteOrders() {
    dummyOrders.deleteAll();
  }

  @Test
  void eventDeliveredAgainAfterCommitKeepsItsNumberAndAddsNothing() {
    DummyOrder order = DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE);
    String originalNumber = dummyOrders.place(order);
    dummyOrders.place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));

    assertThat(dummyOrders.place(order)).isEqualTo(originalNumber).isEqualTo("SUBA260000000001");
    assertThat(dummyOrders.invoiceCount()).isEqualTo(2);
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, ONE_SHARD.code(), FINANCIAL_YEAR))
        .isEqualTo(Map.of("A", 2L));
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void raisingTheShardCountKeepsEarlierNumbersAndSpreadsNewOrdersOverEveryShard() {
    List<DummyOrder> earlierOrders = newOrders(ONE_SHARD, ORDERS_BEFORE_RESHARD);
    List<String> originalNumbers = placeInTurn(earlierOrders);

    List<String> redeliveredNumbers =
        placeInTurn(earlierOrders.stream().map(NumberingEdgeCaseTest::afterReshard).toList());
    placeInTurn(newOrders(ONE_SHARD_RESHARDED_TO_FOUR, ORDERS_AFTER_RESHARD));

    assertThat(redeliveredNumbers).isEqualTo(originalNumbers);
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, ONE_SHARD.code(), FINANCIAL_YEAR))
        .containsOnlyKeys("A", "B", "C", "D");
    assertThat(dummyOrders.invoiceCount()).isEqualTo(ORDERS_BEFORE_RESHARD + ORDERS_AFTER_RESHARD);
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void ordersEitherSideOfTheYearEndPlacedTogetherFillTwoSeparateSeries() throws Exception {
    List<String> numbers =
        new ConcurrentOrders(dummyOrders)
            .place(
                THREADS,
                ORDERS_PER_THREAD,
                takingTurns(
                    () -> DummyOrder.newOrder(ONE_SHARD, LAST_DAY_OF_YEAR),
                    () -> DummyOrder.newOrder(ONE_SHARD, FIRST_DAY_OF_NEXT_YEAR)));

    assertThat(numbers)
        .containsExactlyInAnyOrderElementsOf(
            Stream.of(LAST_DAY_OF_YEAR, FIRST_DAY_OF_NEXT_YEAR)
                .map(FinancialYear::of)
                .flatMap(year -> ONE_SHARD.firstNumbers(year, ORDERS / 2).stream())
                .toList());
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void twoBusinessUnitsLoadedTogetherEachCountOnlyTheirOwnOrders() throws Exception {
    List<String> numbers =
        new ConcurrentOrders(dummyOrders)
            .place(
                THREADS,
                ORDERS_PER_THREAD,
                takingTurns(
                    () -> DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE),
                    () -> DummyOrder.newOrder(FOUR_SHARDS, INVOICE_DATE)));

    assertThat(numbers).hasSize(ORDERS).doesNotHaveDuplicates();
    assertThat(reconciliation.lastNumbersByShard(ENTITY_CODE, ONE_SHARD.code(), FINANCIAL_YEAR))
        .isEqualTo(Map.of("A", (long) ORDERS / 2));
    assertThat(reconciliation.issuedCount(ENTITY_CODE, FOUR_SHARDS.code(), FINANCIAL_YEAR))
        .isEqualTo(ORDERS / 2);
    assertThat(reconciliation.problems()).isEmpty();
  }

  private List<String> placeInTurn(List<DummyOrder> orders) {
    return orders.stream().map(dummyOrders::place).toList();
  }

  private static List<DummyOrder> newOrders(TestBusinessUnit businessUnit, int count) {
    return Stream.generate(() -> DummyOrder.newOrder(businessUnit, INVOICE_DATE))
        .limit(count)
        .toList();
  }

  /** The same event delivered again after its business unit moved to four shards. */
  private static DummyOrder afterReshard(DummyOrder order) {
    return new DummyOrder(ONE_SHARD_RESHARDED_TO_FOUR, order.sourceEventId(), order.invoiceDate());
  }

  /**
   * Hands out orders from each source in turn, safely across threads, so an even total splits
   * exactly in half.
   */
  private static Supplier<DummyOrder> takingTurns(
      Supplier<DummyOrder> first, Supplier<DummyOrder> second) {
    AtomicInteger handedOut = new AtomicInteger();
    return () -> (handedOut.getAndIncrement() % 2 == 0 ? first : second).get();
  }
}
