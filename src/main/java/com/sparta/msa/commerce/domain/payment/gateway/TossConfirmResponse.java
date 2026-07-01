package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TossConfirmResponse(
    String paymentKey,
    String orderId,
    String status,
    BigDecimal totalAmount,
    OffsetDateTime approvedAt
) {}
