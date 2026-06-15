package com.sparta.msa.commerce.global.exception;

import org.springframework.http.HttpStatus;

public interface ExceptionCode {

  HttpStatus getStatus();

  String getMessage();

  String name();
}
