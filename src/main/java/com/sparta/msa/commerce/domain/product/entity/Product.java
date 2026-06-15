package com.sparta.msa.commerce.domain.product.entity;

import static lombok.AccessLevel.PRIVATE;

import com.sparta.msa.commerce.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
@Table(name = "products")
public class Product extends BaseEntity {

  @Comment("상품명")
  @Column(nullable = false, length = 100)
  String name;

  @Comment("상품 설명")
  @Column(length = 1000)
  String description;

  @Comment("정가")
  @Column(nullable = false, precision = 12, scale = 0)
  BigDecimal price;

  @Comment("판매 상태")
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  ProductStatus status;
}
