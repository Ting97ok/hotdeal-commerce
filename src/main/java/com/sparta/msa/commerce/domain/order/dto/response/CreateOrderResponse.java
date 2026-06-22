package com.sparta.msa.commerce.domain.order.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreateOrderResponse(
    Long orderId,
    String orderNo,
    BigDecimal orderAmount,
    LocalDateTime expiresAt
) {
}
