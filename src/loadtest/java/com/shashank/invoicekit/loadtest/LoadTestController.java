package com.shashank.invoicekit.loadtest;

import com.shashank.invoicekit.numbering.domain.exception.SeriesBusyException;
import com.shashank.invoicekit.numbering.domain.exception.SeriesExhaustedException;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the temporary numbering load test. k6 places dummy orders here, and the
 * runner script checks and clears the series between phases.
 */
@RestController
@Profile("loadtest")
@RequestMapping("/loadtest")
class LoadTestController {

  private final LoadTestOrders orders;
  private final LoadTestReconciliation reconciliation;

  LoadTestController(LoadTestOrders orders, LoadTestReconciliation reconciliation) {
    this.orders = orders;
    this.reconciliation = reconciliation;
  }

  @PostMapping("/orders")
  PlacedOrder place(@Valid @RequestBody LoadTestOrder order) {
    return new PlacedOrder(orders.place(order));
  }

  @GetMapping("/reconciliation")
  LoadTestReconciliation.Report reconciliation() {
    return reconciliation.report();
  }

  @DeleteMapping("/orders")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteAll() {
    orders.deleteAll();
  }

  /** The counter stayed locked too long. Retrying may succeed, so k6 sees a retryable failure. */
  @ExceptionHandler(SeriesBusyException.class)
  ProblemDetail seriesBusy(SeriesBusyException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, error.getMessage());
  }

  @ExceptionHandler(SeriesExhaustedException.class)
  ProblemDetail seriesExhausted(SeriesExhaustedException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalidOrder(IllegalArgumentException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
  }

  /**
   * The answer to a placed order.
   *
   * @param number invoice number, such as {@code SUBA260000000001}
   */
  record PlacedOrder(String number) {}
}
