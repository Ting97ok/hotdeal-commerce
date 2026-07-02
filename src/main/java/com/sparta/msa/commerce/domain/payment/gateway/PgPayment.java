package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PgPayment(
    PgPaymentStatus status,
    BigDecimal totalAmount,
    LocalDateTime approvedAt
) {}
