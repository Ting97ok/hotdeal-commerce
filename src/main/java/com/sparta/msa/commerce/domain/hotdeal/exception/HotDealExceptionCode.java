package com.sparta.msa.commerce.domain.hotdeal.exception;

import com.sparta.msa.commerce.global.exception.ExceptionCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum HotDealExceptionCode implements ExceptionCode {

  HOTDEAL_PERIOD_OVERLAP(HttpStatus.CONFLICT, "같은 상품의 진행 중 핫딜과 판매 기간이 겹칩니다."),
  INVALID_HOTDEAL_PERIOD(HttpStatus.BAD_REQUEST, "판매 시작 시각은 종료 시각보다 앞서야 합니다.");

  final HttpStatus status;
  final String message;
}
