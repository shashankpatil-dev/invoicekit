package com.shashank.invoicekit.numbering.flow;

import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places dummy orders from many threads at once, like a busy consumer group handling a burst.
 *
 * <p>Under load an order can find its series locked past the lock timeout and fail with {@link
 * SeriesBusyException}. Kafka would redeliver it, so here it is retried up to a limit and counted.
 */
final class ConcurrentOrders {

  private static final Logger log = LoggerFactory.getLogger(ConcurrentOrders.class);

  /** Tries per order before a busy series fails the test, unless the test sets its own limit. */
  private static final int DEFAULT_MAX_TRIES = 5;

  private final DummyOrders dummyOrders;
  private final int maxTries;
  private final AtomicInteger busyRetries = new AtomicInteger();

  ConcurrentOrders(DummyOrders dummyOrders) {
    this(dummyOrders, DEFAULT_MAX_TRIES);
  }

  /**
   * Places orders, trying each up to {@code maxTries} times while its series is busy. A limit of 1
   * never retries, so any busy series fails the test.
   */
  ConcurrentOrders(DummyOrders dummyOrders, int maxTries) {
    this.dummyOrders = dummyOrders;
    this.maxTries = maxTries;
  }

  /**
   * Starts all threads at the same moment, once every one is ready. Each places {@code
   * ordersPerThread} orders from {@code nextOrder}, one after another. Returns every number handed
   * out.
   */
  List<String> place(int threads, int ordersPerThread, Supplier<DummyOrder> nextOrder)
      throws InterruptedException, ExecutionException {
    CyclicBarrier allReady = new CyclicBarrier(threads);
    Callable<List<String>> worker =
        () -> {
          allReady.await();
          return placeInTurn(ordersPerThread, nextOrder);
        };
    long startedAt = System.nanoTime();
    List<String> numbers = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      for (Future<List<String>> placed : executor.invokeAll(Collections.nCopies(threads, worker))) {
        numbers.addAll(placed.get());
      }
    }
    log.info(
        "{} threads placed {} orders in {} ms with {} busy-series retries",
        threads,
        numbers.size(),
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
        busyRetries.get());
    return numbers;
  }

  /** How many times an order was tried again because its series was busy. */
  int busyRetries() {
    return busyRetries.get();
  }

  private List<String> placeInTurn(int orders, Supplier<DummyOrder> nextOrder) {
    return Stream.generate(nextOrder).limit(orders).map(this::placeRetryingBusy).toList();
  }

  /** Places the order, trying again while its series is busy, up to {@code maxTries} times. */
  private String placeRetryingBusy(DummyOrder order) {
    for (int attempt = 1; ; attempt++) {
      try {
        return dummyOrders.place(order);
      } catch (SeriesBusyException busy) {
        if (attempt == maxTries) {
          throw busy;
        }
        busyRetries.incrementAndGet();
      }
    }
  }
}
