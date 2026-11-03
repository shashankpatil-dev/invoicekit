package com.shashank.invoicekit.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Keeps the numbering module's hexagonal layers apart and the module independent. */
@AnalyzeClasses(
    packages = "com.shashank.invoicekit",
    importOptions = ImportOption.DoNotIncludeTests.class)
class NumberingArchitectureTest {

  /** Numbering rules stay plain Java, so they can be tested and reused without any framework. */
  @ArchTest
  static final ArchRule domainHasNoFrameworkDependencies =
      noClasses()
          .that()
          .resideInAPackage("..numbering.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "io.micrometer..",
              "java.sql..",
              "javax.sql..",
              "com.fasterxml.jackson..");

  /** The application talks to storage only through its ports, so adapters can be swapped. */
  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..numbering.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..numbering.adapter..");

  /** Other modules call numbering, never the other way round, so numbering builds on its own. */
  @ArchTest
  static final ArchRule moduleDoesNotDependOnOtherFeatures =
      noClasses()
          .that()
          .resideInAPackage("..numbering..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..invoicekit.invoice..",
              "..invoicekit.adaption..",
              "..invoicekit.documentengine..",
              "..invoicekit.archive..");

  /** Ports are contracts that adapters fulfil, so they hold no implementation of their own. */
  @ArchTest
  static final ArchRule outboundPortsAreInterfaces =
      classes()
          .that()
          .resideInAPackage("..numbering.application.port.outbound..")
          .should()
          .beInterfaces();

  /** Every outbound adapter exists to fulfil a port, so none is wired in around the ports. */
  @ArchTest
  static final ArchRule outboundAdaptersImplementPorts =
      classes()
          .that()
          .resideInAPackage("..numbering.adapter.outbound..")
          .and()
          .areTopLevelClasses()
          .should()
          .implement(resideInAPackage("..numbering.application.port.outbound.."));
}
