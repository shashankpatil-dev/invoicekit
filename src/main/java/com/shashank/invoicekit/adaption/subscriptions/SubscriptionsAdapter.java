package com.shashank.invoicekit.adaption.subscriptions;

import com.shashank.invoicekit.invoice.application.adaption.InvoiceAdapter;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceDraft;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceField;
import com.shashank.invoicekit.invoice.domain.entity.InvoiceRequested;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The {@code subscriptions} BU bills seat-based plans monthly or annually. Its events already carry
 * final seat counts and coupon discounts, so lines and extras pass straight through after the BU's
 * own rules are checked.
 */
@Component
public class SubscriptionsAdapter implements InvoiceAdapter {

  private static final String BUSINESS_UNIT = "subscriptions";

  /** The billing cycles this BU sells. */
  private static final Set<String> BILLING_CYCLES = Set.of("MONTHLY", "ANNUAL");

  private static final String BILLING_CYCLE_KEY = InvoiceField.BILLING_CYCLE.key();

  @Override
  public String businessUnit() {
    return BUSINESS_UNIT;
  }

  /** Rejects the event, listing every problem, when it breaks this BU's rules. */
  @Override
  public InvoiceDraft adapt(InvoiceRequested event) {
    List<String> problems =
        Stream.concat(unknownBillingCycle(event), linesWithoutServicePeriod(event)).toList();
    if (!problems.isEmpty()) {
      throw new RejectedEventException(RejectionCode.VALIDATION_FAILED, problems);
    }
    return new InvoiceDraft(event, event.lineItems(), event.attributes());
  }

  private static Stream<String> unknownBillingCycle(InvoiceRequested event) {
    return Optional.ofNullable(event.attributes().get(BILLING_CYCLE_KEY))
        .filter(cycle -> !BILLING_CYCLES.contains(cycle))
        .map(cycle -> BILLING_CYCLE_KEY + " must be MONTHLY or ANNUAL, but was " + cycle)
        .stream();
  }

  /** Every subscription charge covers a period, which the invoice must show. */
  private static Stream<String> linesWithoutServicePeriod(InvoiceRequested event) {
    return IntStream.range(0, event.lineItems().size())
        .filter(index -> !event.lineItems().get(index).hasServicePeriod())
        .mapToObj(
            index -> "line " + (index + 1) + " needs servicePeriodStart and servicePeriodEnd");
  }
}
