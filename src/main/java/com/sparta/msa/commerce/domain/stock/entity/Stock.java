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
@Table(name = "stock")
public class Stock extends BaseEntity {

  @Comment("핫딜 ID (논리 참조, 1:1)")
  @Column(name = "hot_deal_id", nullable = false, unique = true)
  Long hotDealId;

  @Comment("잔여 수량 (경합 대상)")
  @Column(name = "remaining_quantity", nullable = false)
  int remainingQuantity;

  @Comment("낙관락 버전")
  @Version
  @Column(nullable = false)
  Long version;

  public static Stock create(Long hotDealId, int totalQuantity) {
    return Stock.builder()
        .hotDealId(hotDealId)
        .remainingQuantity(totalQuantity)
        .build();
  }
}
