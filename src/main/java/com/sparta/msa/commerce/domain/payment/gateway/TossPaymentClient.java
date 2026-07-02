package com.sparta.msa.commerce.domain.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

@Service
@RequiredArgsConstructor
public class TossPaymentClient implements PaymentGatewayClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // 돈이 확실히 안 빠진 "사용자 거절"만. 돈 빠짐(ALREADY_PROCESSED_PAYMENT)·처리오류·설정버그는 넣지 않음(→ InDoubt).
  private static final Set<String> REJECT_CODES = Set.of(
      "REJECT_CARD_COMPANY",
      "REJECT_CARD_PAYMENT",
      "REJECT_ACCOUNT_PAYMENT",
      "REJECT_TOSSPAY_INVALID_ACCOUNT",
      "INVALID_CARD_NUMBER",
      "INVALID_CARD_EXPIRATION",
      "INVALID_STOPPED_CARD",
      "INVALID_CARD_LOST_OR_STOLEN",
      "INVALID_REJECT_CARD",
      "INVALID_PASSWORD",
      "INVALID_ACCOUNT_INFO_RE_REGISTER",
      "BELOW_MINIMUM_AMOUNT",
      "EXCEED_MAX_CARD_INSTALLMENT_PLAN",
      "EXCEED_MAX_DAILY_PAYMENT_COUNT",
      "EXCEED_MAX_PAYMENT_AMOUNT",
      "EXCEED_MAX_AMOUNT",
      "EXCEED_MAX_ONE_DAY_AMOUNT",
      "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
      "EXCEED_MAX_AUTH_COUNT",
      "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT",
      "EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT",
      "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT",
      "INVALID_CARD_INSTALLMENT_PLAN",
      "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
      "NOT_ALLOWED_POINT_USE",
      "RESTRICTED_TRANSFER_ACCOUNT",
      "NOT_AVAILABLE_BANK",
      "NOT_AVAILABLE_PAYMENT",
      "FDS_ERROR",
      "NOT_FOUND_PAYMENT_SESSION");

  private final TossHttpClient tossHttpClient;

  @Override
  public PgConfirmResult confirm(String paymentKey, String orderId, BigDecimal amount) {
    try {
      TossConfirmResponse response = tossHttpClient.confirm(
          new TossConfirmRequest(paymentKey, orderId, amount), paymentKey);
      return new PgConfirmResult.Approved(
          response.paymentKey(),
          null,
          response.totalAmount(),
          response.approvedAt().toLocalDateTime()
      );
    } catch (HttpStatusCodeException e) {
      String errorCode = extractErrorCode(e.getResponseBodyAsString());
      return errorCode != null && REJECT_CODES.contains(errorCode)
          ? new PgConfirmResult.Rejected()
          : new PgConfirmResult.InDoubt();
    } catch (ResourceAccessException e) {
      return requestNotDelivered(e.getCause())
          ? new PgConfirmResult.GatewayError()
          : new PgConfirmResult.InDoubt();
    }
  }

  @Override
  public PgPayment getPayment(String paymentKey) {
    TossConfirmResponse response = tossHttpClient.getPayment(paymentKey);
    return new PgPayment(
        toStatus(response.status()),
        response.totalAmount(),
        response.approvedAt() != null ? response.approvedAt().toLocalDateTime() : null
    );
  }

  private PgPaymentStatus toStatus(String tossStatus) {
    try {
      return PgPaymentStatus.valueOf(tossStatus);
    } catch (IllegalArgumentException | NullPointerException e) {
      return PgPaymentStatus.UNKNOWN;
    }
  }

  private String extractErrorCode(String body) {
    try {
      JsonNode code = OBJECT_MAPPER.readTree(body).get("code");
      return code != null ? code.asText() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private boolean requestNotDelivered(Throwable cause) {
    return cause instanceof ConnectException
        || cause instanceof HttpConnectTimeoutException
        || cause instanceof UnknownHostException;
  }
}
