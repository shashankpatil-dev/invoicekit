package com.shashank.invoicekit.invoice.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.shashank.invoicekit.bootstrap.configuration.IntakeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Starts the intake configuration against the test database and checks a mismatch between adapters
 * and configs stops it starting. It runs as a child of the flow test context, which supplies the
 * database, the JSON mapper, numbering and the {@code subscriptions} adapter.
 */
@IntakeFlowTest
class IntakeStartupCheckTest {

  @Autowired private ApplicationContext flowContext;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void deleteExtraConfig() {
    jdbcClient.sql("DELETE FROM adapter_config WHERE bu = 'usage'").update();
  }

  @Test
  void startsWhenEveryConfigHasAnAdapter() {
    intakeContext().run(intake -> assertThat(intake).hasNotFailed());
  }

  @Test
  void refusesToStartWhenAConfigHasNoAdapter() {
    jdbcClient
        .sql(
            """
            INSERT INTO adapter_config (bu, version, allowed_fields, number_format, active)
            VALUES ('usage', 1, '[]', 'USG{fy}{seq:10}', true)
            """)
        .update();

    intakeContext()
        .run(
            intake ->
                assertThat(intake)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("business unit usage has a config but no adapter"));
  }

  private ApplicationContextRunner intakeContext() {
    return new ApplicationContextRunner()
        .withParent(flowContext)
        .withUserConfiguration(IntakeConfiguration.class);
  }
}
