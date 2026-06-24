package com.sparta.msa.commerce.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record ConfirmPaymentRequest(
    @NotBlank String paymentKey,
    @NotBlank String orderId,
    @NotNull @Positive BigDecimal amount
) {}
