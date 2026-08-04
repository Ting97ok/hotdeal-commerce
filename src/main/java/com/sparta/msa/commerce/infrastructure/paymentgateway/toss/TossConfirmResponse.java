package com.sparta.msa.commerce.infrastructure.paymentgateway.toss;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TossConfirmResponse(
    String paymentKey,
    String orderId,
    String status,
    BigDecimal totalAmount,
    OffsetDateTime approvedAt
) {}
