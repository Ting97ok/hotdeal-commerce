package com.sparta.msa.commerce.global.exception;

import static com.sparta.msa.commerce.global.exception.DomainExceptionCode.CONCURRENT_UPDATE_CONFLICT;
import static com.sparta.msa.commerce.global.exception.DomainExceptionCode.DATA_INTEGRITY_VIOLATION;
import static com.sparta.msa.commerce.global.exception.DomainExceptionCode.SERVER_ERROR;
import static com.sparta.msa.commerce.global.exception.DomainExceptionCode.VALIDATION_ERROR;

import com.sparta.msa.commerce.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiResponse<Void>> handleDomainException(DomainException ex) {
    log.warn("[DomainException] : code={}, message={}", ex.getCode(), ex.getMessage());
    return ApiResponse.fail(ex.getHttpStatus(), ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException ex) {
    String errorMessage = extractErrorMessages(ex);
    log.warn("[ValidationException] : {}", errorMessage);
    return fail(VALIDATION_ERROR, errorMessage);
  }

  @ExceptionHandler(BindException.class)
  public ResponseEntity<ApiResponse<Void>> handleBindException(BindException ex) {
    String errorMessage = extractErrorMessages(ex);
    log.warn("[BindException] : {}", errorMessage);
    return fail(VALIDATION_ERROR, errorMessage);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
      ConstraintViolationException ex) {
    String errorMessage = ex.getConstraintViolations().stream()
        .map(ConstraintViolation::getMessage)
        .collect(Collectors.joining(", "));
    log.warn("[ConstraintViolationException] : {}", errorMessage);
    return fail(VALIDATION_ERROR, errorMessage);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
      MissingServletRequestParameterException ex) {
    log.warn("[MissingServletRequestParameterException] : {}", ex.getMessage());
    return fail(VALIDATION_ERROR, ex.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
      HttpMessageNotReadableException ex) {
    log.warn("[HttpMessageNotReadableException] : {}", ex.getMessage());
    return fail(VALIDATION_ERROR, "요청 본문을 읽을 수 없습니다.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(
      MethodArgumentTypeMismatchException ex) {
    log.warn("[MethodArgumentTypeMismatchException] : {}", ex.getMessage());
    return fail(VALIDATION_ERROR, "요청 파라미터 타입이 올바르지 않습니다.");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(
      DataIntegrityViolationException ex) {
    log.error("[DataIntegrityViolation] : {}", ex.getMostSpecificCause().getMessage());
    return fail(DATA_INTEGRITY_VIOLATION, DATA_INTEGRITY_VIOLATION.getMessage());
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiResponse<Void>> handleObjectOptimisticLockingFailureException(
      ObjectOptimisticLockingFailureException ex) {
    log.warn("[OptimisticLockConflict] : {}", ex.getMessage());
    return fail(CONCURRENT_UPDATE_CONFLICT, CONCURRENT_UPDATE_CONFLICT.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
    log.error("[Exception] : ", ex);
    String message = ex.getMessage() != null ? ex.getMessage() : SERVER_ERROR.getMessage();
    return fail(SERVER_ERROR, message);
  }

  private ResponseEntity<ApiResponse<Void>> fail(ExceptionCode code, String message) {
    return ApiResponse.fail(code.getStatus(), code.name(), message);
  }

  private String extractErrorMessages(BindException ex) {
    return ex.getBindingResult()
        .getAllErrors()
        .stream()
        .map(DefaultMessageSourceResolvable::getDefaultMessage)
        .collect(Collectors.joining(", "));
  }
}
