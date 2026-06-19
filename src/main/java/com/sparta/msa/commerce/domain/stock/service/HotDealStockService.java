package com.sparta.msa.commerce.domain.stock.service;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HotDealStockService {

  private final HotDealStockRepository hotDealStockRepository;

  @Transactional
  public void createForHotDeal(Long hotDealId, int totalQuantity) {
    HotDealStock hotDealStock = HotDealStock.create(hotDealId, totalQuantity);
    hotDealStockRepository.save(hotDealStock);
  }
}
