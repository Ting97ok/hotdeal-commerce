package com.sparta.msa.commerce.domain.payment.exception;

import com.sparta.msa.commerce.global.exception.ExceptionCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum PaymentExceptionCode implements ExceptionCode {

  PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "결제 게이트웨이 오류가 발생했습니다."),
  PAYMENT_REJECTED(HttpStatus.PAYMENT_REQUIRED, "결제가 거부됐습니다.");

  final HttpStatus status;
  final String message;
}
