package com.sparta.msa.commerce.domain.order.exception;

import com.sparta.msa.commerce.global.exception.ExceptionCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum OrderExceptionCode implements ExceptionCode {

  ALREADY_PURCHASED(HttpStatus.CONFLICT, "이미 구매한 핫딜입니다."),
  EXCEEDS_PURCHASE_LIMIT(HttpStatus.BAD_REQUEST, "1주문 최대 수량을 초과했습니다."),
  ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
  AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 주문 금액과 일치하지 않습니다."),
  ORDER_STATUS_CONFLICT(HttpStatus.CONFLICT, "이미 처리된 주문입니다.");

  final HttpStatus status;
  final String message;
}
