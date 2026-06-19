package com.sparta.msa.commerce.domain.stock.entity;

import static lombok.AccessLevel.PRIVATE;

import com.sparta.msa.commerce.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
@Table(name = "product_stock")
public class ProductStock extends BaseEntity {

  @Comment("상품 ID (논리 참조, 1:1)")
  @Column(name = "product_id", nullable = false, unique = true)
  Long productId;

  @Comment("실물 수량 (창고 실재 수)")
  @Column(name = "on_hand_quantity", nullable = false)
  int onHandQuantity;

  @Comment("예약 수량 (핫딜에 떼어 둔 수)")
  @Column(name = "reserved_quantity", nullable = false)
  int reservedQuantity;

  @Comment("낙관락 버전")
  @Version
  @Column(nullable = false)
  Long version;

  public static ProductStock create(Long productId, int onHandQuantity) {
    return ProductStock.builder()
        .productId(productId)
        .onHandQuantity(onHandQuantity)
        .reservedQuantity(0)
        .build();
  }

  public void reserve(int quantity) {
    // TODO(stock-insufficient): 가용(on_hand−reserved) < quantity 거부 → INSUFFICIENT_PRODUCT_STOCK 미구현
    this.reservedQuantity += quantity;
  }
}
