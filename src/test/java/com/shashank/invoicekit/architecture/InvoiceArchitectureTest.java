package com.shashank.invoicekit.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Keeps the invoice module's hexagonal layers apart and the BU adapters independent of it, as
 * docs/INTAKE.md section 11 lists. That numbering never imports invoice is checked in {@link
 * NumberingArchitectureTest}.
 */
@AnalyzeClasses(
    packages = "com.shashank.invoicekit",
    importOptions = ImportOption.DoNotIncludeTests.class)
class InvoiceArchitectureTest {

  /**
   * Invoice rules stay plain Java. Jackson's annotations are allowed, because they only describe
   * the payload's JSON shape and pull in no mapper.
   */
  @ArchTest
  static final ArchRule domainHasNoFrameworkDependencies =
      noClasses()
          .that()
          .resideInAPackage("..invoice.domain..")
          .should()
          .dependOnClassesThat(
              resideInAnyPackage(
                      "org.springframework..",
                      "java.sql..",
                      "javax.sql..",
                      "org.apache.kafka..",
                      "io.micrometer..")
                  .or(
                      resideInAPackage("com.fasterxml.jackson..")
                          .and(not(resideInAPackage("com.fasterxml.jackson.annotation..")))));

  /** The application talks to storage only through its ports, so adapters can be swapped. */
  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..invoice.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..invoice.adapter..");

  /** BU adapters plug into invoice, never the other way round, so a new BU is only new files. */
  @ArchTest
  static final ArchRule invoiceDoesNotDependOnBusinessUnitAdapters =
      noClasses()
          .that()
          .resideInAPackage("..invoicekit.invoice..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.shashank.invoicekit.adaption..");

  /**
   * A BU adapter only maps events to drafts, so it needs nothing beyond the contract it fulfils.
   */
  @ArchTest
  static final ArchRule businessUnitAdaptersUseOnlyTheContract =
      classes()
          .that()
          .resideInAPackage("com.shashank.invoicekit.adaption..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "com.shashank.invoicekit.adaption..",
              "..invoice.domain..",
              "..invoice.application.adaption..",
              "org.springframework.stereotype..",
              "java..");

  /** Ports are contracts that adapters fulfil, so they hold no implementation of their own. */
  @ArchTest
  static final ArchRule outboundPortsAreInterfaces =
      classes()
          .that()
          .resideInAPackage("..invoice.application.port.outbound..")
          .should()
          .beInterfaces();

  /** Every outbound adapter exists to fulfil a port, so none is wired in around the ports. */
  @ArchTest
  static final ArchRule outboundAdaptersImplementPorts =
      classes()
          .that()
          .resideInAPackage("..invoice.adapter.outbound..")
          .and()
          .areTopLevelClasses()
          .should()
          .implement(resideInAPackage("..invoice.application.port.outbound.."));
}
