package com.shashank.invoicekit.invoice.application.service;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.DOMESTIC_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.EXPORT_EVENT;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.GST_START;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.acmeMaharashtra;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.domesticEvent;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.event;
import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.seedDeclarations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shashank.invoicekit.invoice.domain.entity.Declaration;
import com.shashank.invoicekit.invoice.domain.entity.InvoicePayload.ResolvedDeclaration;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.exception.RejectedEventException;
import com.shashank.invoicekit.invoice.domain.exception.RejectionCode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks which declarations apply, how placeholders are filled and the print order. */
class DeclarationResolverTest {

  private final DeclarationResolver resolver = new DeclarationResolver();

  @Test
  void aSubscriptionsInvoiceGetsTheSubscriptionPeriodDeclaration() {
    assertThat(
            codes(resolver.resolve(seedDeclarations(), event(DOMESTIC_EVENT), acmeMaharashtra())))
        .containsExactly("GENERAL", "SUBSCRIPTION_PERIOD", "ELECTRONIC_INVOICE");
  }

  @Test
  void anExportGetsTheLutDeclarationWithTheEntitysLutNumber() {
    List<ResolvedDeclaration> resolved =
        resolver.resolve(seedDeclarations(), event(EXPORT_EVENT), acmeMaharashtra());

    assertThat(resolved)
        .contains(
            new ResolvedDeclaration(
                "EXPORT_LUT",
                "Supply meant for export under Letter of Undertaking without payment of integrated"
                    + " tax. LUT: AD2700000000000"));
    assertThat(codes(resolved)).containsExactly("GENERAL", "EXPORT_LUT", "ELECTRONIC_INVOICE");
  }

  @Test
  void reverseChargeAddsItsDeclaration() {
    assertThat(
            codes(
                resolver.resolve(
                    seedDeclarations(),
                    domesticEvent(json -> json.put("reverseCharge", true)),
                    acmeMaharashtra())))
        .containsExactly("GENERAL", "REVERSE_CHARGE", "SUBSCRIPTION_PERIOD", "ELECTRONIC_INVOICE");
  }

  @Test
  void printsBySortOrderThenCode() {
    List<Declaration> candidates =
        List.of(
            declaration("C", "Third", 20),
            declaration("B", "Second", 20),
            declaration("A", "First", 10));

    assertThat(codes(resolver.resolve(candidates, event(DOMESTIC_EVENT), acmeMaharashtra())))
        .containsExactly("A", "B", "C");
  }

  @Test
  void skipsDeclarationsOutsideTheirDates() {
    Declaration ended =
        new Declaration(
            "OLD", "Old", null, null, null, null, 10, GST_START, LocalDate.of(2026, 10, 31));
    Declaration future =
        new Declaration("NEW", "New", null, null, null, null, 10, LocalDate.of(2026, 11, 2), null);

    assertThat(resolver.resolve(List.of(ended, future), event(DOMESTIC_EVENT), acmeMaharashtra()))
        .isEmpty();
  }

  @Test
  void fillsEveryPlaceholderFromTheEntity() {
    Declaration declaration = declaration("SUPPLIER", "Issued by {legalName}, GSTIN {gstin}.", 10);

    assertThat(resolver.resolve(List.of(declaration), event(DOMESTIC_EVENT), acmeMaharashtra()))
        .containsExactly(
            new ResolvedDeclaration(
                "SUPPLIER", "Issued by Acme Cloud Private Limited, GSTIN 27AAAAA0000A1Z5."));
  }

  @Test
  void insertsValuesWithSpecialCharactersAsWritten() {
    LegalEntity entity = acmeMaharashtra("AD2700000000000", "Pay $100 by C:\\bank");
    Declaration declaration = declaration("TERMS", "Terms: {terms}", 10);

    assertThat(resolver.resolve(List.of(declaration), event(DOMESTIC_EVENT), entity))
        .containsExactly(new ResolvedDeclaration("TERMS", "Terms: Pay $100 by C:\\bank"));
  }

  @Test
  void rejectsEveryPlaceholderWithoutAValue() {
    List<Declaration> candidates =
        List.of(
            declaration("SIGNED", "Signed by {authorisedSignatory}", 10),
            declaration("ODD", "See {website} and {logo}", 20));

    assertThatThrownBy(() -> resolver.resolve(candidates, event(DOMESTIC_EVENT), acmeMaharashtra()))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected -> {
              assertThat(rejected.code()).isEqualTo(RejectionCode.VALIDATION_FAILED);
              assertThat(rejected.reasons())
                  .containsExactly(
                      "declaration SIGNED has no value for {authorisedSignatory}",
                      "declaration ODD has no value for {website}",
                      "declaration ODD has no value for {logo}");
            });
  }

  @Test
  void anExportFromAnEntityWithoutLutIsRejected() {
    LegalEntity entity = acmeMaharashtra(null, null);

    assertThatThrownBy(() -> resolver.resolve(seedDeclarations(), event(EXPORT_EVENT), entity))
        .isInstanceOfSatisfying(
            RejectedEventException.class,
            rejected ->
                assertThat(rejected.reasons())
                    .containsExactly("declaration EXPORT_LUT has no value for {lutNumber}"));
  }

  private static List<String> codes(List<ResolvedDeclaration> resolved) {
    return resolved.stream().map(ResolvedDeclaration::code).toList();
  }

  private static Declaration declaration(String code, String body, int sortOrder) {
    return new Declaration(code, body, null, null, null, null, sortOrder, GST_START, null);
  }
}
