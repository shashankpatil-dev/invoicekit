package com.shashank.invoicekit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Keeps the document engine independent, as the LLD requires. */
@AnalyzeClasses(
    packages = "com.shashank.invoicekit",
    importOptions = ImportOption.DoNotIncludeTests.class)
class DocumentEngineArchitectureTest {

  @ArchTest
  static final ArchRule domainHasNoFrameworkDependencies =
      noClasses()
          .that()
          .resideInAPackage("..documentengine.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "com.fasterxml.jackson..");

  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..documentengine.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..documentengine.adapter..");

  @ArchTest
  static final ArchRule moduleDoesNotKnowAboutInvoices =
      noClasses()
          .that()
          .resideInAPackage("..documentengine..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..invoicekit.invoice..", "..invoicekit.adaption..");
}
