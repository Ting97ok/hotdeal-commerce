package com.sparta.msa.commerce.domain.stock.service;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.STOCK_NOT_FOUND;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 동시성 차감 전략 비교용(ADR-0010) — 운영 기본은 조건부 차감, 낙관락은 고경합서 충돌 대량 거절로 배제.
@Service
@ConditionalOnProperty(name = "stock.deduct.strategy", havingValue = "optimistic")
public class OptimisticHotDealStockService extends AbstractHotDealStockService {

  public OptimisticHotDealStockService(HotDealStockRepository hotDealStockRepository) {
    super(hotDealStockRepository);
  }

  @Override
  @Transactional
  public void deduct(Long hotDealId, int quantity) {
    HotDealStock stock = getStock(hotDealId);
    stock.deduct(quantity);
  }

  @Override
  @Transactional
  public void restore(Long hotDealId, int quantity) {
    HotDealStock stock = getStock(hotDealId);
    stock.restore(quantity);
  }

  private HotDealStock getStock(Long hotDealId) {
    return hotDealStockRepository.findByHotDealId(hotDealId)
        .orElseThrow(() -> new DomainException(STOCK_NOT_FOUND));
  }
}
