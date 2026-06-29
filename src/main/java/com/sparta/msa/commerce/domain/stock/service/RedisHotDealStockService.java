package com.sparta.msa.commerce.domain.stock.service;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.SOLD_OUT;

import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRedisRepository;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 동시성 차감 전략 비교용(ADR-0010) — 운영 기본은 조건부 차감, Redis는 고RPS 승격 후보.
@Service
@ConditionalOnProperty(name = "stock.deduct.strategy", havingValue = "redis")
public class RedisHotDealStockService extends AbstractHotDealStockService {

  private final HotDealStockRedisRepository redisRepository;

  public RedisHotDealStockService(HotDealStockRepository hotDealStockRepository,
      HotDealStockRedisRepository redisRepository) {
    super(hotDealStockRepository);
    this.redisRepository = redisRepository;
  }

  @Override
  @Transactional
  public void createForHotDeal(Long hotDealId, int totalQuantity) {
    super.createForHotDeal(hotDealId, totalQuantity);
    redisRepository.init(hotDealId, totalQuantity);
  }

  @Override
  public void deduct(Long hotDealId, int quantity) {
    if (redisRepository.deduct(hotDealId, quantity) <= 0) {
      throw new DomainException(SOLD_OUT);
    }
  }

  @Override
  public void restore(Long hotDealId, int quantity) {
    redisRepository.restore(hotDealId, quantity);
  }
}
