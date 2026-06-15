package com.sparta.msa.commerce.domain.order.entity;

import static jakarta.persistence.ConstraintMode.NO_CONSTRAINT;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PRIVATE;

import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "orders")
public class Order extends BaseEntity {

  @Comment("주문 번호 (UUID, 토스 orderId 겸용)")
  @Column(name = "order_no", nullable = false, unique = true, length = 36)
  String orderNo;

  @Comment("주문 수량 (현재 1 고정)")
  @Column(nullable = false)
  int quantity;

  @Comment("주문 금액 (특가×수량, 주문 시점 저장)")
  @Column(name = "order_amount", nullable = false, precision = 12, scale = 0)
  BigDecimal orderAmount;

  @Comment("주문 상태 (PENDING/PAID/CANCELED)")
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  OrderStatus status;

  @Comment("취소 사유 (취소 시만)")
  @Enumerated(EnumType.STRING)
  @Column(name = "cancel_reason", length = 30)
  CancelReason cancelReason;

  @Comment("미결제 만료 시각")
  @Column(name = "expires_at", nullable = false)
  LocalDateTime expiresAt;

  @Comment("주문자 (논리 참조)")
  @ManyToOne(fetch = LAZY)
  @JoinColumn(name = "user_id", foreignKey = @ForeignKey(NO_CONSTRAINT))
  User user;

  @Comment("대상 핫딜 (논리 참조)")
  @ManyToOne(fetch = LAZY)
  @JoinColumn(name = "hot_deal_id", foreignKey = @ForeignKey(NO_CONSTRAINT))
  HotDeal hotDeal;
}
