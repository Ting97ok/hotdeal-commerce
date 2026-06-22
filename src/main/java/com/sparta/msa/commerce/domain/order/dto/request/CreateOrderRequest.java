package com.sparta.msa.commerce.domain.order.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
    @NotNull Long productId,
    @NotNull @Min(1) Integer quantity
) {
}
