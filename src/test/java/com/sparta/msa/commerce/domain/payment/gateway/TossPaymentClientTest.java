package com.sparta.msa.commerce.domain.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
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
    ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
        .withConnectTimeout(Duration.ofMillis(500))
        .withReadTimeout(Duration.ofMillis(300));
    RestClient restClient = RestClient.builder()
        .baseUrl(server.url("/").toString())
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .build();
    TossHttpClient tossHttpClient = HttpServiceProxyFactory
        .builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(TossHttpClient.class);
    tossPaymentClient = new TossPaymentClient(tossHttpClient);
  }

  @AfterEach
  void tearDown() {
    try {
      server.shutdown();
    } catch (Exception ignored) {
    }
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

  @Test
  @DisplayName("토스 4xx 거부 응답을 Rejected 결과로 매핑한다")
  void mapsRejectedOn4xx() {
    server.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"code":"REJECT_CARD_COMPANY","message":"카드사에서 승인을 거부했습니다."}
            """));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.Rejected.class);
  }

  @Test
  @DisplayName("토스 read 타임아웃(응답 유실)을 InDoubt 결과로 매핑한다")
  void mapsInDoubtOnReadTimeout() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.InDoubt.class);
  }

  @Test
  @DisplayName("토스 5xx 즉답(통신오류)을 GatewayError 결과로 매핑한다")
  void mapsGatewayErrorOn5xx() {
    server.enqueue(new MockResponse()
        .setResponseCode(500)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"내부 처리 오류"}
            """));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.GatewayError.class);
  }

  @Test
  @DisplayName("토스 연결 실패(요청 미도달)를 GatewayError 결과로 매핑한다")
  void mapsGatewayErrorOnConnectFailure() throws IOException {
    server.shutdown();

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.GatewayError.class);
  }
}
