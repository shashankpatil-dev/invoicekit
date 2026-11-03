package com.shashank.invoicekit.numbering.flow;

import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.FOUR_SHARDS;
import static com.shashank.invoicekit.numbering.flow.TestBusinessUnit.ONE_SHARD;
import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.numbering.flow.DummyOrders.StoredNumber;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Numbers dummy orders through the real numbering service and PostgreSQL, the way the invoice
 * module will, and checks what each invoice ends up holding.
 */
@NumberingFlowTest
class DummyOrderNumberingTest {

  private static final Logger log = LoggerFactory.getLogger(DummyOrderNumberingTest.class);

  private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 11, 4);
  private static final int ORDERS = 10;

  @Autowired private DummyOrders dummyOrders;
  @Autowired private SeriesReconciliation reconciliation;

  @AfterEach
  void deleteOrders() {
    dummyOrders.deleteAll();
  }

  @Test
  void firstOrderGetsTheFirstNumberAndItsInvoiceStoresIt() {
    DummyOrder order = DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE);

    assertThat(place(order)).isEqualTo("SUBA260000000001");
    assertThat(dummyOrders.storedNumber(order))
        .isEqualTo(new StoredNumber("A", 1, "SUBA260000000001"));
  }

  @Test
  void ordersPlacedOneAfterAnotherCountUpWithoutGaps() {
    List<String> numbers =
        Stream.generate(() -> DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE))
            .limit(ORDERS)
            .map(this::place)
            .toList();

    assertThat(numbers)
        .containsExactlyElementsOf(
            LongStream.rangeClosed(1, ORDERS).mapToObj("SUBA26%010d"::formatted).toList());
    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void eachShardedOrderShowsTheShardStoredOnItsInvoice() {
    Stream.generate(() -> DummyOrder.newOrder(FOUR_SHARDS, INVOICE_DATE))
        .limit(ORDERS)
        .forEach(
            order -> {
              String number = place(order);
              StoredNumber stored = dummyOrders.storedNumber(order);
              assertThat(number)
                  .isEqualTo(stored.invoiceNumber())
                  .startsWith(FOUR_SHARDS.prefix() + stored.shard());
            });

    assertThat(reconciliation.problems()).isEmpty();
  }

  @Test
  void thirtyFirstMarchStaysInTheOldYearAndFirstAprilStartsANewSeries() {
    place(DummyOrder.newOrder(ONE_SHARD, INVOICE_DATE));

    assertThat(place(DummyOrder.newOrder(ONE_SHARD, LocalDate.of(2027, 3, 31))))
        .isEqualTo("SUBA260000000002");
    assertThat(place(DummyOrder.newOrder(ONE_SHARD, LocalDate.of(2027, 4, 1))))
        .isEqualTo("SUBA270000000001");
    assertThat(reconciliation.problems()).isEmpty();
  }

  /** Places the order and logs its number, so the test run shows every number handed out. */
  private String place(DummyOrder order) {
    String number = dummyOrders.place(order);
    log.info(
        "Dummy order on {} dated {} got invoice number {}",
        order.businessUnit().code(),
        order.invoiceDate(),
        number);
    return number;
  }
}
