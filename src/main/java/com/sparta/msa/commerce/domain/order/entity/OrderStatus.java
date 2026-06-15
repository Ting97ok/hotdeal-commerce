package com.sparta.msa.commerce.domain.order.entity;

public enum OrderStatus {
  PENDING,   // 결제 대기 (재고 선점 완료, 미결제)
  PAID,      // 결제 완료
  CANCELED   // 취소 (결제 실패·만료)
}
