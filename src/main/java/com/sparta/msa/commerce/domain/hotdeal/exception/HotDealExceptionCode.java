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
  INVALID_HOTDEAL_PERIOD(HttpStatus.BAD_REQUEST, "판매 시작 시각은 종료 시각보다 앞서야 합니다."),
  INVALID_DEAL_PRICE(HttpStatus.BAD_REQUEST, "특가는 정가보다 낮아야 합니다."),
  NO_ACTIVE_DEAL(HttpStatus.NOT_FOUND, "구매 가능한 활성 핫딜이 없습니다."),
  HOTDEAL_CANCELED(HttpStatus.CONFLICT, "취소된 핫딜은 결제할 수 없습니다.");

  final HttpStatus status;
  final String message;
}
