package com.sparta.msa.commerce.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ApiResponse<T> {

  @Builder.Default
  Boolean result = true;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  T data;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  Error error;

  public static <T> ApiResponse<T> ok() {
    return ApiResponse.<T>builder().build();
  }

  public static <T> ApiResponse<T> ok(T data) {
    return ApiResponse.<T>builder()
        .data(data)
        .build();
  }

  public static <T> ResponseEntity<ApiResponse<T>> fail(HttpStatus httpStatus, String code,
      String message) {
    return ResponseEntity.status(httpStatus)
        .body(ApiResponse.<T>builder()
            .result(false)
            .error(Error.of(code, message))
            .build());
  }

  public static ApiResponse<Void> failBody(String code, String message) {
    return ApiResponse.<Void>builder()
        .result(false)
        .error(Error.of(code, message))
        .build();
  }

  public record Error(String code, String message) {
    public static Error of(String code, String message) {
      return new Error(code, message);
    }
  }
}
