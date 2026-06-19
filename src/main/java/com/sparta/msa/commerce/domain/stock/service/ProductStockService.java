package com.sparta.msa.commerce.domain.stock.service;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.STOCK_NOT_FOUND;

import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import com.sparta.msa.commerce.domain.stock.repository.ProductStockRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductStockService {

  private final ProductStockRepository productStockRepository;

  @Transactional
  public void reserve(Long productId, int quantity) {
    ProductStock productStock = productStockRepository.findByProductId(productId)
        .orElseThrow(() -> new DomainException(STOCK_NOT_FOUND));
    productStock.reserve(quantity);
  }
}
