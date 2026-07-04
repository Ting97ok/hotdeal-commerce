package com.sparta.msa.commerce.domain.payment.gateway;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

public interface TossHttpClient {

  @PostExchange("/v1/payments/confirm")
  TossConfirmResponse confirm(@RequestBody TossConfirmRequest request,
                              @RequestHeader("Idempotency-Key") String idempotencyKey);

  @GetExchange("/v1/payments/{paymentKey}")
  TossConfirmResponse getPayment(@PathVariable String paymentKey);

  @GetExchange("/v1/payments/orders/{orderId}")
  TossConfirmResponse getPaymentByOrderId(@PathVariable String orderId);
}
