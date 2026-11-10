package com.shashank.invoicekit.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/** Checks that application.yml loads every file in resources/config. */
class ConfigurationFilesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void loadsEveryConfigurationFile() {
    contextRunner.run(
        context -> {
          Environment environment = context.getEnvironment();

          assertThat(environment.getProperty("spring.datasource.url")).contains("5433/invoicekit");
          assertThat(environment.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
          assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6380");
          assertThat(environment.getProperty("invoicekit.storage.bucket")).isEqualTo("invoices");
          assertThat(environment.getProperty("invoicekit.render.timeout")).isEqualTo("25s");
          assertThat(environment.getProperty("invoicekit.outbox.mode")).isEqualTo("polling");
          assertThat(environment.getProperty("invoicekit.notification.smtp-port"))
              .isEqualTo("1025");
          assertThat(environment.getProperty("invoicekit.numbering.lock-timeout")).isEqualTo("2s");
          assertThat(environment.getProperty("invoicekit.numbering.statement-timeout"))
              .isEqualTo("5s");
          assertThat(environment.getProperty("invoicekit.numbering.utilisation-warning"))
              .isEqualTo("0.8");
          assertThat(environment.getProperty("invoicekit.intake.reference-cache-ttl"))
              .isEqualTo("5m");
          assertThat(environment.getProperty("invoicekit.intake.max-payload-bytes"))
              .isEqualTo("900000");
          assertThat(environment.getProperty("server.port")).isEqualTo("8081");
        });
  }
}
