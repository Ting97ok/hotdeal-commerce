package com.sparta.msa.commerce.domain.stock;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.SOLD_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.global.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "stock.deduct.strategy=conditional")
@DisplayName("조건부 UPDATE 재고 차감")
class ConditionalDeductIntegrationTest {

  @Autowired
  HotDealStockService hotDealStockService;

  @Autowired
  HotDealStockRepository hotDealStockRepository;

  @BeforeEach
  void setUp() {
    hotDealStockRepository.deleteAll();
  }

  @Test
  @DisplayName("조건부 UPDATE 로 차감하면 잔여가 줄어든다")
  void conditionalDeductReducesRemaining() {
    Long hotDealId = 1L;
    hotDealStockService.createForHotDeal(hotDealId, 10);

    hotDealStockService.deduct(hotDealId, 3);

    HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDealId).orElseThrow();
    assertThat(stock.getRemainingQuantity()).isEqualTo(7);
  }

  @Test
  @DisplayName("잔여가 부족하면 SOLD_OUT 으로 차감이 거부된다")
  void conditionalDeductRejectsWhenInsufficient() {
    Long hotDealId = 1L;
    hotDealStockService.createForHotDeal(hotDealId, 5);

    assertThatThrownBy(() -> hotDealStockService.deduct(hotDealId, 10))
        .isInstanceOf(DomainException.class)
        .hasFieldOrPropertyWithValue("exceptionCode", SOLD_OUT);

    HotDealStock stock = hotDealStockRepository.findByHotDealId(hotDealId).orElseThrow();
    assertThat(stock.getRemainingQuantity()).isEqualTo(5);
  }
}
