package com.sparta.msa.commerce.domain.payment.entity;

public enum PaymentStatus {
  DONE,     // 승인 성공
  FAILED,   // 승인 실패·해소로 실패 확정
  IN_DOUBT  // 결과 미확정 (토스 타임아웃·응답 유실 — 해소로 확정)
}
