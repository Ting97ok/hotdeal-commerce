package com.sparta.msa.commerce.global.exception;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DomainException extends RuntimeException {

  HttpStatus httpStatus;
  String code;
  ExceptionCode exceptionCode;

  public DomainException(ExceptionCode exceptionCode) {
    super(exceptionCode.getMessage());
    this.httpStatus = exceptionCode.getStatus();
    this.code = exceptionCode.name();
    this.exceptionCode = exceptionCode;
  }
}
