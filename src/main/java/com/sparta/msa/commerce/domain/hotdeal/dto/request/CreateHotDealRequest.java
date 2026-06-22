package com.sparta.msa.commerce.domain.hotdeal.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreateHotDealRequest(
    @NotNull Long productId,
    @NotNull @DecimalMin("1") @Digits(integer = 12, fraction = 0) BigDecimal dealPrice,
    @NotNull @Min(1) @Max(100000) Integer totalQuantity,
    @NotNull @Min(1) @Max(100000) Integer maxPerOrder,
    @NotNull LocalDateTime startAt,
    @NotNull LocalDateTime endAt
) {
}
