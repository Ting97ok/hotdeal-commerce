package com.sparta.msa.commerce.domain.payment.gateway;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface TossHttpClient {

  @PostExchange("/v1/payments/confirm")
  TossConfirmResponse confirm(@RequestBody TossConfirmRequest request);
}
