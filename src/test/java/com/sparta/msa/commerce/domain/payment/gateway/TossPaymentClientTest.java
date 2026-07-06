package com.sparta.msa.commerce.domain.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  @DisplayName("토스 FDS_ERROR(403, 위험거래 차단)를 Rejected 결과로 매핑한다")
  void mapsRejectedOnFdsError() {
    server.enqueue(new MockResponse()
        .setResponseCode(403)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"code":"FDS_ERROR","message":"위험거래가 감지되어 결제가 제한됩니다."}
            """));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.Rejected.class);
  }

  @ParameterizedTest(name = "{0}({1}) -> InDoubt")
  @CsvSource({
      "PROVIDER_ERROR, 400",
      "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING, 500",
      "FAILED_INTERNAL_SYSTEM_PROCESSING, 500",
      "UNKNOWN_PAYMENT_ERROR, 500"
  })
  @DisplayName("토스 결제상태 불명 에러코드(4xx·5xx 혼재)를 InDoubt 결과로 매핑한다")
  void mapsInDoubtOnStateUnknownCodes(String code, int status) {
    server.enqueue(new MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody("{\"code\":\"" + code + "\",\"message\":\"결제 상태 불명\"}"));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.InDoubt.class);
  }

  @Test
  @DisplayName("토스 read 타임아웃(응답 유실)을 InDoubt 결과로 매핑한다")
  void mapsInDoubtOnReadTimeout() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.InDoubt.class);
  }

  @Test
  @DisplayName("응답을 받았으나 거절 코드가 아니면(순수 5xx 등) InDoubt로 매핑한다")
  void mapsInDoubtOnUnrecognized5xx() {
    server.enqueue(new MockResponse()
        .setResponseCode(502)
        .setBody("Bad Gateway"));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.InDoubt.class);
  }

  @Test
  @DisplayName("NOT_FOUND_PAYMENT(존재하지 않는 결제=돈 안 빠짐 확정)는 Rejected로 매핑한다 — 위조 키의 영구 IN_DOUBT 차단")
  void mapsRejectedOnNotFoundPayment() {
    server.enqueue(new MockResponse()
        .setResponseCode(404)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 입니다."}
            """));

    PgConfirmResult result = tossPaymentClient.confirm("fake_pk", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.Rejected.class);
  }

  @Test
  @DisplayName("ALREADY_PROCESSED_PAYMENT(이미 결제됨=돈 빠짐)는 Rejected가 아니라 InDoubt로 매핑한다")
  void mapsInDoubtOnAlreadyProcessedPayment() {
    server.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"code":"ALREADY_PROCESSED_PAYMENT","message":"이미 처리된 결제 입니다."}
            """));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.InDoubt.class);
  }

  @Test
  @DisplayName("거절 목록에 없는 알 수 없는 code는 InDoubt로 매핑한다(안전 기본값)")
  void mapsInDoubtOnUnknownCode() {
    server.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"code":"SOME_UNRECOGNIZED_CODE","message":"처음 보는 에러"}
            """));

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.InDoubt.class);
  }

  @Test
  @DisplayName("토스 연결 실패(요청 미도달)를 GatewayError 결과로 매핑한다")
  void mapsGatewayErrorOnConnectFailure() throws IOException {
    server.shutdown();

    PgConfirmResult result = tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    assertThat(result).isInstanceOf(PgConfirmResult.GatewayError.class);
  }

  @Test
  @DisplayName("confirm 요청에 Idempotency-Key 헤더로 paymentKey를 전송한다")
  void sendsIdempotencyKeyHeader() throws InterruptedException {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"paymentKey":"toss_pk_123","orderId":"order-abc","status":"DONE","totalAmount":19800,"approvedAt":"2026-07-01T15:00:00+09:00"}
            """));

    tossPaymentClient.confirm("toss_pk_123", "order-abc", new BigDecimal("19800"));

    RecordedRequest recorded = server.takeRequest();
    assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo("toss_pk_123");
  }

  @Test
  @DisplayName("토스 결제 조회 DONE 응답을 PgPaymentStatus.DONE으로 매핑한다")
  void mapsGetPaymentStatusDone() {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"paymentKey":"toss_pk_123","orderId":"order-abc","status":"DONE","totalAmount":19800,"approvedAt":"2026-07-02T10:00:00+09:00"}
            """));

    PgPayment result = tossPaymentClient.findPayment("toss_pk_123").orElseThrow();

    assertThat(result.status()).isEqualTo(PgPaymentStatus.DONE);
  }

  @ParameterizedTest(name = "조회 status {0}")
  @CsvSource({"IN_PROGRESS", "EXPIRED", "ABORTED", "CANCELED"})
  @DisplayName("토스 결제 조회 status를 같은 이름의 PgPaymentStatus로 매핑한다")
  void mapsGetPaymentStatus(String tossStatus) {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("{\"paymentKey\":\"toss_pk_123\",\"orderId\":\"order-abc\",\"status\":\"" + tossStatus + "\",\"totalAmount\":19800}"));

    PgPayment result = tossPaymentClient.findPayment("toss_pk_123").orElseThrow();

    assertThat(result.status().name()).isEqualTo(tossStatus);
  }

  @Test
  @DisplayName("주문번호 결제 조회 DONE 응답을 paymentKey 포함 PgPayment로 매핑한다")
  void mapsFindPaymentByOrderIdDone() {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"paymentKey":"toss_pk_orphan","orderId":"order-abc","status":"DONE","totalAmount":19800,"approvedAt":"2026-07-02T10:00:00+09:00"}
            """));

    Optional<PgPayment> result = tossPaymentClient.findPaymentByOrderId("order-abc");

    assertThat(result).isPresent();
    assertThat(result.get().paymentKey()).isEqualTo("toss_pk_orphan");
    assertThat(result.get().status()).isEqualTo(PgPaymentStatus.DONE);
    assertThat(result.get().totalAmount()).isEqualByComparingTo("19800");
  }

  @Test
  @DisplayName("주문번호 결제 조회 404(결제 없음)는 빈 결과로 매핑한다")
  void mapsFindPaymentByOrderIdNotFound() {
    server.enqueue(new MockResponse()
        .setResponseCode(404)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 입니다."}
            """));

    Optional<PgPayment> result = tossPaymentClient.findPaymentByOrderId("order-none");

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("토스 결제 조회의 알 수 없는 status는 UNKNOWN으로 매핑한다(안전 기본값)")
  void mapsGetPaymentUnknownStatus() {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""
            {"paymentKey":"toss_pk_123","orderId":"order-abc","status":"SOME_NEW_STATUS","totalAmount":19800}
            """));

    PgPayment result = tossPaymentClient.findPayment("toss_pk_123").orElseThrow();

    assertThat(result.status()).isEqualTo(PgPaymentStatus.UNKNOWN);
  }
}
