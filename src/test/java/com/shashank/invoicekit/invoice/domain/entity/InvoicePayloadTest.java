package com.shashank.invoicekit.invoice.domain.entity;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_PAYLOAD;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.JSON;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.KARNATAKA;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.tree;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.PlaceOfSupply;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.Supplier;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks the saved payload's JSON shape and the number added after assembly. */
class InvoicePayloadTest {

  private static final InvoicePayload SAVED =
      JSON.convertValue(tree(DOMESTIC_PAYLOAD), InvoicePayload.class);

  @Test
  void readsAndWritesTheSupersetPayloadUnchanged() {
    assertThat(JSON.<JsonNode>valueToTree(SAVED)).isEqualTo(tree(DOMESTIC_PAYLOAD));
  }

  @Test
  void withInvoiceNumberReturnsANumberedCopy() {
    InvoicePayload unnumbered = SAVED.withInvoiceNumber(null);

    InvoicePayload numbered = unnumbered.withInvoiceNumber("SUBA260000000142");

    assertThat(numbered).isEqualTo(SAVED);
    assertThat(unnumbered.invoiceNumber()).isNull();
  }

  @Test
  void leavesAbsentValuesOutOfTheJson() {
    JsonNode json = JSON.valueToTree(SAVED.withInvoiceNumber(null));

    assertThat(json.has("invoiceNumber")).isFalse();
    assertThat(json.has("poNumber")).isFalse();
    assertThat(json.get("placeOfSupply").has("countryCode")).isFalse();
    assertThat(json.get("supplier").has("authorisedSignatory")).isFalse();
  }

  @Test
  void leavesEmptyBankDetailsOutOfTheJson() {
    Supplier supplier =
        new Supplier(
            "E", "Name", null, "G", "S", "27", "A", "R", "C", null, null, null, Map.of(), null,
            null, null);

    assertThat(JSON.<JsonNode>valueToTree(supplier).has("bankDetails")).isFalse();
  }

  @Test
  void aDomesticPlaceOfSupplyIsTheState() {
    assertThat(PlaceOfSupply.ofState(KARNATAKA))
        .isEqualTo(new PlaceOfSupply("Karnataka", "29", null, null));
  }

  @Test
  void anExportPlaceOfSupplyIsTheCountryInEnglish() {
    assertThat(PlaceOfSupply.ofCountry("DE"))
        .isEqualTo(new PlaceOfSupply(null, null, "Germany", "DE"));
  }
}
