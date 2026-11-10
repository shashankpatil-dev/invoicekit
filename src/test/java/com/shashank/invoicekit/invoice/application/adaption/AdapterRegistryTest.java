package com.shashank.invoicekit.invoice.application.adaption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks how events find their BU's adapter. */
class AdapterRegistryTest {

  private static final StubAdapter SUBSCRIPTIONS = new StubAdapter("subscriptions");
  private static final StubAdapter USAGE = new StubAdapter("usage");

  @Test
  void findsTheAdapterForABusinessUnit() {
    AdapterRegistry registry = new AdapterRegistry(List.of(SUBSCRIPTIONS, USAGE));

    assertThat(registry.forBusinessUnit("usage")).isSameAs(USAGE);
  }

  @Test
  void rejectsAnEventForABusinessUnitWithoutAnAdapter() {
    AdapterRegistry registry = new AdapterRegistry(List.of(SUBSCRIPTIONS));

    assertThatThrownBy(() -> registry.forBusinessUnit("marketplace"))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.UNKNOWN_BU);
              assertThat(rejected.reasons())
                  .containsExactly("No adapter serves business unit marketplace");
            });
  }

  @Test
  void refusesTwoAdaptersForOneBusinessUnit() {
    List<StubAdapter> adapters = List.of(SUBSCRIPTIONS, new StubAdapter("subscriptions"));

    assertThatThrownBy(() -> new AdapterRegistry(adapters))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Business unit subscriptions has two adapters: "
                + StubAdapter.class.getName()
                + " and "
                + StubAdapter.class.getName());
  }
}
