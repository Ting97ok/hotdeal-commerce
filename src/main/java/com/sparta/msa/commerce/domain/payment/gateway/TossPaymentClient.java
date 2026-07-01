package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TossPaymentClient implements PaymentGatewayClient {

  private final TossHttpClient tossHttpClient;

  @Override
  public PgConfirmResult confirm(String paymentKey, String orderId, BigDecimal amount) {
    TossConfirmResponse response = tossHttpClient.confirm(new TossConfirmRequest(paymentKey, orderId, amount));
    return new PgConfirmResult.Approved(
        response.paymentKey(),
        null,
        response.totalAmount(),
        response.approvedAt().toLocalDateTime()
    );
  }
}
