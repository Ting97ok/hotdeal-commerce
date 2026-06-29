package com.sparta.msa.commerce.domain.stock;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.SOLD_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.msa.commerce.RedisTestcontainerConfig;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.global.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestcontainerConfig.class)
@TestPropertySource(properties = "stock.deduct.strategy=redis")
@DisplayName("Redis 재고 전략")
class RedisStockServiceIntegrationTest {

  @Autowired
  HotDealStockService hotDealStockService;

  @Autowired
  HotDealStockRepository hotDealStockRepository;

  @Autowired
  StringRedisTemplate redisTemplate;

  @BeforeEach
  void setUp() {
    hotDealStockRepository.deleteAll();
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  @DisplayName("Redis 전략으로 차감하면 Redis 잔여가 줄어든다")
  void redisDeductReducesRemaining() {
    Long hotDealId = 1L;
    hotDealStockService.createForHotDeal(hotDealId, 10);

    hotDealStockService.deduct(hotDealId, 3);

    String remaining = redisTemplate.opsForValue().get("hotdeal:stock:" + hotDealId);
    assertThat(remaining).isEqualTo("7");
  }

  @Test
  @DisplayName("잔여가 부족하면 SOLD_OUT 으로 차감이 거부된다")
  void redisDeductRejectsWhenInsufficient() {
    Long hotDealId = 1L;
    hotDealStockService.createForHotDeal(hotDealId, 5);

    assertThatThrownBy(() -> hotDealStockService.deduct(hotDealId, 10))
        .isInstanceOf(DomainException.class)
        .hasFieldOrPropertyWithValue("exceptionCode", SOLD_OUT);

    String remaining = redisTemplate.opsForValue().get("hotdeal:stock:" + hotDealId);
    assertThat(remaining).isEqualTo("5");
  }
}
