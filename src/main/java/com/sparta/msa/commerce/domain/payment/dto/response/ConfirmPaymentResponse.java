package com.sparta.msa.commerce.domain.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ConfirmPaymentResponse(
    Long paymentId,
    Long orderId,
    BigDecimal amount,
    LocalDateTime approvedAt
) {}
