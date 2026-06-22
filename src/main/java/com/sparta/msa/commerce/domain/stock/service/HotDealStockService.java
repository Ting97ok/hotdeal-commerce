package com.sparta.msa.commerce.domain.stock.service;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.STOCK_NOT_FOUND;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
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

  @Transactional
  public void deduct(Long hotDealId, int quantity) {
    HotDealStock hotDealStock = hotDealStockRepository.findByHotDealId(hotDealId)
        .orElseThrow(() -> new DomainException(STOCK_NOT_FOUND));
    hotDealStock.deduct(quantity);
  }
}
