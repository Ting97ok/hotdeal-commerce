package com.sparta.msa.commerce.domain.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@DisplayName("토스 결제 어댑터 (실HTTP)")
class TossPaymentClientTest {

  private MockWebServer server;
  private TossPaymentClient tossPaymentClient;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    RestClient restClient = RestClient.builder()
        .baseUrl(server.url("/").toString())
        .build();
    TossHttpClient tossHttpClient = HttpServiceProxyFactory
        .builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(TossHttpClient.class);
    tossPaymentClient = new TossPaymentClient(tossHttpClient);
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  @DisplayName("토스 승인 성공(2xx) 응답을 Approved 결과로 매핑한다")
  void mapsApprovedOnSuccess() {
    String body = """
        {
          "paymentKey": "toss_pk_123",
          "orderId": "order-abc",
          "status": "DONE",
          "totalAmount": 19800,
          "approvedAt": "2026-07-01T15:00:00+09:00"
        }
        """;
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.Approved.class);
    PgConfirmResult.Approved approved = (PgConfirmResult.Approved) result;
    assertThat(approved.pgPaymentKey()).isEqualTo("toss_pk_123");
    assertThat(approved.amount()).isEqualByComparingTo("19800");
    assertThat(approved.approvedAt()).isNotNull();
  }
}
