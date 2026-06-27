package com.sparta.msa.commerce.domain.stock.service;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public abstract class AbstractHotDealStockService implements HotDealStockService {

  protected final HotDealStockRepository hotDealStockRepository;

  @Override
  @Transactional
  public void createForHotDeal(Long hotDealId, int totalQuantity) {
    hotDealStockRepository.save(HotDealStock.create(hotDealId, totalQuantity));
  }
}
