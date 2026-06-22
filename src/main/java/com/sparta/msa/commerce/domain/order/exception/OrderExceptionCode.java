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
  EXCEEDS_PURCHASE_LIMIT(HttpStatus.BAD_REQUEST, "1주문 최대 수량을 초과했습니다.");

  final HttpStatus status;
  final String message;
}
