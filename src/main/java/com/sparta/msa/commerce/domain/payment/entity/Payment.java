package com.sparta.msa.commerce.domain.payment.entity;

import static lombok.AccessLevel.PRIVATE;

import com.sparta.msa.commerce.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Getter
@Builder(access = PRIVATE)
@NoArgsConstructor
@AllArgsConstructor(access = PRIVATE)
@FieldDefaults(level = PRIVATE)
@DynamicInsert
@DynamicUpdate
@Table(name = "payments")
public class Payment extends BaseEntity {

  @Comment("결제 금액")
  @Column(nullable = false, precision = 12, scale = 0)
  BigDecimal amount;

  @Comment("결제 상태")
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  PaymentStatus status;

  @Comment("PG 거래 키")
  @Column(name = "pg_payment_key", unique = true, length = 200)
  String pgPaymentKey;

  @Comment("멱등키 (승인 재시도용)")
  @Column(name = "idempotency_key", length = 200)
  String idempotencyKey;

  @Comment("승인 시각")
  @Column(name = "approved_at")
  LocalDateTime approvedAt;

  @Comment("주문 ID (FK 값, 객체 탐색 불필요)")
  @Column(name = "order_id", nullable = false)
  Long orderId;

  public static Payment create(Long orderId, BigDecimal amount, String pgPaymentKey,
      String idempotencyKey, LocalDateTime approvedAt) {
    return Payment.builder()
        .orderId(orderId)
        .amount(amount)
        .status(PaymentStatus.DONE)
        .pgPaymentKey(pgPaymentKey)
        .idempotencyKey(idempotencyKey)
        .approvedAt(approvedAt)
        .build();
  }
}
