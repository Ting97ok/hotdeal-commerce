package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PgConfirmResult(
    String pgPaymentKey,
    String idempotencyKey,
    BigDecimal amount,
    LocalDateTime approvedAt
) {}
