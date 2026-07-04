package com.sparta.msa.commerce.domain.payment.dto.response;

import com.sparta.msa.commerce.domain.payment.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ConfirmPaymentResponse(
    Long paymentId,
    Long orderId,
    BigDecimal amount,
    PaymentStatus status,
    LocalDateTime approvedAt
) {}
