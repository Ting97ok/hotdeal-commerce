package com.sparta.msa.commerce.domain.stock.service;

public interface HotDealStockService {

  void createForHotDeal(Long hotDealId, int totalQuantity);

  void deduct(Long hotDealId, int quantity);

  void restore(Long hotDealId, int quantity);
}
