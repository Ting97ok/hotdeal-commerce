package com.sparta.msa.commerce.domain.stock.service;

import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRedisRepository;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    redisRepository.deduct(hotDealId, quantity);
  }

  @Override
  public void restore(Long hotDealId, int quantity) {
    redisRepository.restore(hotDealId, quantity);
  }
}
