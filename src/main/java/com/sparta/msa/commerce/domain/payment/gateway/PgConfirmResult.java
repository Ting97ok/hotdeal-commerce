package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public sealed interface PgConfirmResult permits PgConfirmResult.Approved, PgConfirmResult.Rejected {

  record Approved(
      String pgPaymentKey,
      String idempotencyKey,
      BigDecimal amount,
      LocalDateTime approvedAt
  ) implements PgConfirmResult {}

  record Rejected() implements PgConfirmResult {}
}
