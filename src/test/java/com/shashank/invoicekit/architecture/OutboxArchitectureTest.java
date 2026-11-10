package com.shashank.invoicekit.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Keeps the outbox module generic and its hexagonal layers apart. Writers such as intake depend on
 * the outbox, never the other way round. See docs/RENDER.md section 4.
 */
@AnalyzeClasses(
    packages = "com.shashank.invoicekit",
    importOptions = ImportOption.DoNotIncludeTests.class)
class OutboxArchitectureTest {

  /** An outbox message stays plain Java, whatever stores or carries it. */
  @ArchTest
  static final ArchRule domainHasNoFrameworkDependencies =
      noClasses()
          .that()
          .resideInAPackage("..outbox.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "java.sql..",
              "javax.sql..",
              "org.apache.kafka..",
              "io.micrometer..",
              "com.fasterxml.jackson..");

  /** The relay and cleanup reach PostgreSQL and Kafka only through ports. */
  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..outbox.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..outbox.adapter..");

  /** The outbox moves any message, so it knows nothing about the features that write them. */
  @ArchTest
  static final ArchRule moduleDoesNotDependOnFeatures =
      noClasses()
          .that()
          .resideInAPackage("..invoicekit.outbox..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..invoicekit.invoice..",
              "..invoicekit.numbering..",
              "..invoicekit.documentengine..",
              "..invoicekit.adaption..",
              "..invoicekit.archive..");

  /** Ports are contracts that adapters fulfil, so they hold no implementation of their own. */
  @ArchTest
  static final ArchRule outboundPortsAreInterfaces =
      classes()
          .that()
          .resideInAPackage("..outbox.application.port.outbound..")
          .should()
          .beInterfaces();

  /** Every outbound adapter exists to fulfil a port, so none is wired in around the ports. */
  @ArchTest
  static final ArchRule outboundAdaptersImplementPorts =
      classes()
          .that()
          .resideInAPackage("..outbox.adapter.outbound..")
          .and()
          .areTopLevelClasses()
          .should()
          .implement(resideInAPackage("..outbox.application.port.outbound.."));
}
