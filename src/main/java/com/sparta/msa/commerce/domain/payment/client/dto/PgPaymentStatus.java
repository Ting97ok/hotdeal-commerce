package com.sparta.msa.commerce.domain.payment.client.dto;

public enum PgPaymentStatus {
  DONE,                // 승인 완료 (돈 나감)
  IN_PROGRESS,         // 인증 완료·confirm 미완성 (우리 쪽 미마무리 → 재시도 대상)
  EXPIRED,             // 10분 내 미승인 만료 (실패 확정)
  ABORTED,             // 승인 실패
  CANCELED,            // 취소
  READY,               // 인증 전
  WAITING_FOR_DEPOSIT, // 가상계좌 입금 대기 (카드 범위 밖)
  UNKNOWN              // 매핑 안 되는 값 (안전 기본값)
}
