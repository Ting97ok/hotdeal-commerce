package com.sparta.msa.commerce.domain.stock.service;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.STOCK_NOT_FOUND;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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
