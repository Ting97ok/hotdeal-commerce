package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class TossPaymentClient implements PaymentGatewayClient {

  @Override
  public PgConfirmResult confirm(String paymentKey, String orderId, BigDecimal amount) {
    // TODO(slice-3): 토스 HTTP 클라이언트 연동 구현
    throw new UnsupportedOperationException("TODO(slice-3): 토스 승인 API 미구현");
  }
}
