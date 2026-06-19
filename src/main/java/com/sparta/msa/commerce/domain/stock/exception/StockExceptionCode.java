package com.sparta.msa.commerce.domain.stock.exception;

import com.sparta.msa.commerce.global.exception.ExceptionCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum StockExceptionCode implements ExceptionCode {

  INSUFFICIENT_PRODUCT_STOCK(HttpStatus.CONFLICT, "상품 가용 재고가 부족합니다."),
  STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "상품 재고 정보를 찾을 수 없습니다.");

  final HttpStatus status;
  final String message;
}
