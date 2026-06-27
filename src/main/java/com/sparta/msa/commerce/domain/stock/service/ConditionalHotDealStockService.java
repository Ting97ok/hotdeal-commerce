package com.sparta.msa.commerce.domain.stock.service;

import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "stock.deduct.strategy", havingValue = "conditional")
public class ConditionalHotDealStockService extends AbstractHotDealStockService {

  public ConditionalHotDealStockService(HotDealStockRepository hotDealStockRepository) {
    super(hotDealStockRepository);
  }

  @Override
  @Transactional
  public void deduct(Long hotDealId, int quantity) {
    hotDealStockRepository.deductConditional(hotDealId, quantity);
  }

  @Override
  @Transactional
  public void restore(Long hotDealId, int quantity) {
    hotDealStockRepository.restoreConditional(hotDealId, quantity);
  }
}
