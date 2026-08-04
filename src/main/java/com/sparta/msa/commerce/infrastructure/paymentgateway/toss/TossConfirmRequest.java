package com.sparta.msa.commerce.infrastructure.paymentgateway.toss;

import java.math.BigDecimal;

public record TossConfirmRequest(
    String paymentKey,
    String orderId,
    BigDecimal amount
) {}
