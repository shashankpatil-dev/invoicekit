package com.shashank.invoicekit.invoice.domain.entity;

import static com.shashank.invoicekit.invoice.domain.IntakeFixtures.acmeMaharashtra;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Checks the values an entity offers to declaration wording. */
class LegalEntityTest {

  @Test
  void offersItsFieldsByName() {
    assertThat(acmeMaharashtra().placeholderValues())
        .containsEntry("lutNumber", "AD2700000000000")
        .containsEntry("legalName", "Acme Cloud Private Limited")
        .containsEntry("gstin", "27AAAAA0000A1Z5")
        .hasSize(14);
  }

  @Test
  void leavesOutFieldsWithoutAValue() {
    assertThat(acmeMaharashtra().placeholderValues()).doesNotContainKey("authorisedSignatory");
  }

  @Test
  void absentBankDetailsAreEmpty() {
    LegalEntity entity =
        new LegalEntity(
            "E", "Name", null, "G", "S", "27", "A", "R", "C", null, null, null, null, null, null,
            null);

    assertThat(entity.bankDetails()).isEmpty();
  }
}
