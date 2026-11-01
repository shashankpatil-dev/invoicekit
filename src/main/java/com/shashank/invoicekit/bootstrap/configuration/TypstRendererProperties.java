package com.shashank.invoicekit.bootstrap.configuration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Typst renderer service.
 *
 * @param url base address of the service
 * @param timeout longest wait for one render, kept below the service's 30 second worker limit
 */
@ConfigurationProperties("invoicekit.render")
public record TypstRendererProperties(URI url, Duration timeout) {}
