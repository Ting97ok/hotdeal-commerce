package com.sparta.msa.commerce.domain.hotdeal.entity;

public enum HotDealStatus {
  ACTIVE,    // 진행 (관리자 취소 전 기본 상태)
  CANCELED   // 관리자 취소 (긴급 중단)
}
