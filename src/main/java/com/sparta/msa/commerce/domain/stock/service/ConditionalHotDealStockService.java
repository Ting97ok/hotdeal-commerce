package com.sparta.msa.commerce.domain.stock.service;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.SOLD_OUT;

import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "stock.deduct.strategy", havingValue = "conditional", matchIfMissing = true)
public class ConditionalHotDealStockService extends AbstractHotDealStockService {

  public ConditionalHotDealStockService(HotDealStockRepository hotDealStockRepository) {
    super(hotDealStockRepository);
  }

  @Override
  @Transactional
  public void deduct(Long hotDealId, int quantity) {
    if (hotDealStockRepository.deductConditional(hotDealId, quantity) == 0) {
      throw new DomainException(SOLD_OUT);
    }
  }

  @Override
  @Transactional
  public void restore(Long hotDealId, int quantity) {
    hotDealStockRepository.restoreConditional(hotDealId, quantity);
  }
}
