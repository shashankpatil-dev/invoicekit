package com.shashank.invoicekit.loadtest;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the temporary numbering load test endpoint.
 *
 * @param entityCode legal entity every dummy order invoices under
 * @param numberFormats number template for each business unit, keyed by business unit
 */
@ConfigurationProperties("invoicekit.loadtest")
public record LoadTestProperties(String entityCode, Map<String, String> numberFormats) {}
