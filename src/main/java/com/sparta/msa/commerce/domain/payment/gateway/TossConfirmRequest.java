package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;

public record TossConfirmRequest(
    String paymentKey,
    String orderId,
    BigDecimal amount
) {}
