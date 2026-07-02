package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;

public interface PaymentGatewayClient {

  PgConfirmResult confirm(String paymentKey, String orderId, BigDecimal amount);

  PgPayment getPayment(String paymentKey);
}
