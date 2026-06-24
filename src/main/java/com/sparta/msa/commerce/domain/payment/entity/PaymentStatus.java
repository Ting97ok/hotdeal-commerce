package com.sparta.msa.commerce.domain.payment.entity;

public enum PaymentStatus {
  PENDING,  // 승인 전 (슬라이스4 활용)
  DONE,     // 승인 성공
  FAILED,   // 승인 실패 (슬라이스4)
  CANCELED  // 취소 (슬라이스4)
}
