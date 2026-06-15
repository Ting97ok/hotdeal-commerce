package com.sparta.msa.commerce.domain.payment.entity;

import static jakarta.persistence.ConstraintMode.NO_CONSTRAINT;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PRIVATE;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

  // TODO(slice-3): 결제 상태·칼럼 확정 (토스 응답 기준 — PaymentStatus enum 도입 등)
  @Comment("결제 상태 (슬라이스 3 확정)")
  @Column(length = 20)
  String status;

  @Comment("PG 거래 키")
  @Column(name = "pg_payment_key", unique = true, length = 200)
  String pgPaymentKey;

  @Comment("멱등키 (승인 재시도용)")
  @Column(name = "idempotency_key", length = 200)
  String idempotencyKey;

  @Comment("승인 시각")
  @Column(name = "approved_at")
  LocalDateTime approvedAt;

  @Comment("주문 (논리 참조, 1:N)")
  @ManyToOne(fetch = LAZY)
  @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(NO_CONSTRAINT))
  Order order;
}
