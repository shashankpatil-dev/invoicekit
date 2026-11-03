package com.shashank.invoicekit.loadtest;

import com.shashank.invoicekit.numbering.domain.entity.NumberFormat;
import com.shashank.invoicekit.numbering.domain.entity.NumberingRule;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The business units load tests may order for, and how each numbers its invoices. Formats come from
 * the load test settings and shard counts from {@code bu_entity}, both read once at start-up.
 */
@Component
@Profile("loadtest")
class LoadTestBusinessUnits {

  private static final String SHARD_COUNT =
      """
      SELECT number_shards FROM bu_entity
      WHERE bu = :businessUnit AND entity_code = :entityCode
      """;

  private final Map<String, NumberingRule> rules;

  LoadTestBusinessUnits(JdbcClient jdbcClient, LoadTestProperties properties) {
    this.rules =
        properties.numberFormats().entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    format ->
                        new NumberingRule(
                            NumberFormat.parse(format.getValue()),
                            shardCount(jdbcClient, properties.entityCode(), format.getKey()))));
  }

  /**
   * How the business unit numbers invoices.
   *
   * @throws IllegalArgumentException if the load test has no settings for the business unit
   */
  NumberingRule ruleFor(String businessUnit) {
    NumberingRule rule = rules.get(businessUnit);
    if (rule == null) {
      throw new IllegalArgumentException(
          "Unknown load test business unit "
              + businessUnit
              + ", expected one of "
              + rules.keySet());
    }
    return rule;
  }

  private static int shardCount(JdbcClient jdbcClient, String entityCode, String businessUnit) {
    return jdbcClient
        .sql(SHARD_COUNT)
        .param("businessUnit", businessUnit)
        .param("entityCode", entityCode)
        .query(Integer.class)
        .single();
  }
}
