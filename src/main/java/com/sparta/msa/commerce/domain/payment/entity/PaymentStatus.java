package com.sparta.msa.commerce.domain.payment.entity;

public enum PaymentStatus {
  PENDING,  // 승인 전 (슬라이스4 활용)
  DONE,     // 승인 성공
  FAILED,   // 승인 실패 (슬라이스4)
  CANCELED, // 취소 (슬라이스4)
  IN_DOUBT  // 결과 미확정 (토스 타임아웃·응답 유실 — B2 대사로 확정)
}
