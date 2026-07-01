package com.sparta.msa.commerce.global.config;

import com.sparta.msa.commerce.domain.payment.gateway.TossHttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@EnableConfigurationProperties(TossProperties.class)
public class TossHttpClientConfig {

  @Bean
  public TossHttpClient tossHttpClient(TossProperties tossProperties) {
    ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
        .withConnectTimeout(tossProperties.connectTimeout())
        .withReadTimeout(tossProperties.readTimeout());
    RestClient restClient = RestClient.builder()
        .baseUrl(tossProperties.baseUrl())
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(tossProperties.secretKey()))
        .build();
    return HttpServiceProxyFactory
        .builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(TossHttpClient.class);
  }

  private String basicAuth(String secretKey) {
    String encoded = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    return "Basic " + encoded;
  }
}
