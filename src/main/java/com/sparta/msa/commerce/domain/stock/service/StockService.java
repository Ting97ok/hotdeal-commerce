package com.sparta.msa.commerce.domain.stock.service;

import com.sparta.msa.commerce.domain.stock.entity.Stock;
import com.sparta.msa.commerce.domain.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockService {

  private final StockRepository stockRepository;

  @Transactional
  public void createForHotDeal(Long hotDealId, int totalQuantity) {
    Stock stock = Stock.create(hotDealId, totalQuantity);
    stockRepository.save(stock);
  }
}
