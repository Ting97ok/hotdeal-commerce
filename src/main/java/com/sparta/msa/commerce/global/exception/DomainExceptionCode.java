package com.sparta.msa.commerce.global.exception;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum DomainExceptionCode implements ExceptionCode {

  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값 검증에 실패했습니다."),
  CONCURRENT_UPDATE_CONFLICT(HttpStatus.CONFLICT, "요청이 동시에 몰려 처리하지 못했습니다. 다시 시도해 주세요."),
  DATA_INTEGRITY_VIOLATION(HttpStatus.INTERNAL_SERVER_ERROR, "데이터 무결성 제약을 위반했습니다."),
  SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

  final HttpStatus status;
  final String message;
}
