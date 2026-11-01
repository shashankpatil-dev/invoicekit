package com.shashank.invoicekit.bootstrap.configuration;

import com.shashank.invoicekit.documentengine.adapter.outbound.TypstDocumentEngine;
import com.shashank.invoicekit.documentengine.application.port.outbound.DocumentEngine;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Connects the document engine to the Typst renderer service. */
@Configuration
public class DocumentEngineConfiguration {

  /**
   * Uses HTTP/1.1 because that's what the renderer speaks. Both timeouts use the same limit, so a
   * stuck renderer can never hold a Java worker forever.
   */
  @Bean
  public DocumentEngine documentEngine(TypstRendererProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.timeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.timeout());

    RestClient client =
        RestClient.builder()
            .baseUrl(properties.url().toString())
            .requestFactory(requestFactory)
            .build();
    return new TypstDocumentEngine(client);
  }
}
