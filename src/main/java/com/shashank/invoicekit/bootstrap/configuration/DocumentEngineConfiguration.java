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

  /** Both timeouts use the same limit, so a stuck renderer can never hold a worker forever. */
  @Bean
  public DocumentEngine documentEngine(TypstRendererProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
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
