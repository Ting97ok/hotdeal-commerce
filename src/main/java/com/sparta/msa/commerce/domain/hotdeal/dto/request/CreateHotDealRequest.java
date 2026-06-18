package com.sparta.msa.commerce.domain.hotdeal.dto.request;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreateHotDealRequest(
    // TODO(hotdeal-validation): Bean Validation(@NotNull/@DecimalMin/@Digits/@Min/@Max) 미부착 → VALIDATION_ERROR 슬라이스에서 추가
    Long productId,
    BigDecimal dealPrice,
    Integer totalQuantity,
    LocalDateTime startAt,
    LocalDateTime endAt
) {
}
