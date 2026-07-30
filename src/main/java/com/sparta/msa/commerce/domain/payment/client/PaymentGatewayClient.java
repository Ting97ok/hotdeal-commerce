package com.sparta.msa.commerce.domain.payment.client;

import com.sparta.msa.commerce.domain.payment.client.dto.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.client.dto.PgPayment;
import java.math.BigDecimal;
import java.util.Optional;

public interface PaymentGatewayClient {

  PgConfirmResult confirm(String paymentKey, String orderId, BigDecimal amount);

  Optional<PgPayment> findPayment(String paymentKey);

  Optional<PgPayment> findPaymentByOrderId(String orderId);
}
