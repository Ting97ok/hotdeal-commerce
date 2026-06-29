package com.sparta.msa.commerce.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.msa.commerce.domain.stock.service.ConditionalHotDealStockService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("재고 차감 기본 전략")
class DefaultStockStrategyIntegrationTest {

  @Autowired
  HotDealStockService hotDealStockService;

  @Test
  @DisplayName("stock.deduct.strategy 미설정 시 기본 전략은 벤치마크로 선정한 조건부 차감이다")
  void defaultStrategyIsConditional() {
    assertThat(hotDealStockService).isInstanceOf(ConditionalHotDealStockService.class);
  }
}
