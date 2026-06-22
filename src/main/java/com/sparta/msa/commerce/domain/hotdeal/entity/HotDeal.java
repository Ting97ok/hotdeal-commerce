package com.sparta.msa.commerce.domain.hotdeal.entity;

import static com.sparta.msa.commerce.domain.hotdeal.exception.HotDealExceptionCode.INVALID_DEAL_PRICE;
import static com.sparta.msa.commerce.domain.hotdeal.exception.HotDealExceptionCode.INVALID_HOTDEAL_PERIOD;
import static jakarta.persistence.ConstraintMode.NO_CONSTRAINT;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PRIVATE;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.global.entity.BaseEntity;
import com.sparta.msa.commerce.global.exception.DomainException;
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
@Table(name = "hot_deals")
public class HotDeal extends BaseEntity {

  @Comment("특가")
  @Column(name = "deal_price", nullable = false, precision = 12, scale = 0)
  BigDecimal dealPrice;

  @Comment("총 한정 수량 (등록 후 불변)")
  @Column(name = "total_quantity", nullable = false)
  int totalQuantity;

  @Comment("1주문 최대 수량")
  @Column(name = "max_per_order", nullable = false)
  int maxPerOrder;

  @Comment("판매 시작 시각")
  @Column(name = "start_at", nullable = false)
  LocalDateTime startAt;

  @Comment("판매 종료 시각")
  @Column(name = "end_at", nullable = false)
  LocalDateTime endAt;

  @Comment("상태 (ACTIVE/CANCELED)")
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  HotDealStatus status;

  @Comment("긴급 중단 시각 (취소 시만)")
  @Column(name = "canceled_at")
  LocalDateTime canceledAt;

  @Comment("대상 상품 (논리 참조)")
  @ManyToOne(fetch = LAZY)
  @JoinColumn(name = "product_id", foreignKey = @ForeignKey(NO_CONSTRAINT))
  Product product;

  public static HotDeal create(CreateHotDealRequest request, Product product) {
    validatePeriod(request.startAt(), request.endAt());
    validateDealPrice(request.dealPrice(), product.getPrice());
    return HotDeal.builder()
        .product(product)
        .dealPrice(request.dealPrice())
        .totalQuantity(request.totalQuantity())
        .maxPerOrder(request.maxPerOrder())
        .startAt(request.startAt())
        .endAt(request.endAt())
        .status(HotDealStatus.ACTIVE)
        .build();
  }

  private static void validatePeriod(LocalDateTime startAt, LocalDateTime endAt) {
    if (!startAt.isBefore(endAt)) {
      throw new DomainException(INVALID_HOTDEAL_PERIOD);
    }
  }

  private static void validateDealPrice(BigDecimal dealPrice, BigDecimal listPrice) {
    if (dealPrice.compareTo(listPrice) >= 0) {
      throw new DomainException(INVALID_DEAL_PRICE);
    }
  }
}
