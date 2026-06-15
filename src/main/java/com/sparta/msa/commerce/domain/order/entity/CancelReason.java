package com.sparta.msa.commerce.domain.order.entity;

public enum CancelReason {
  // TODO(slice-2/3): 결제 실패·만료 외 취소 사유 추가 시 확장
  PAYMENT_FAILED,   // 결제 실패로 취소
  EXPIRED           // 미결제 만료로 취소
}
