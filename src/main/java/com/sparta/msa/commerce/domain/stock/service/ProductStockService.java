package com.sparta.msa.commerce.domain.stock.service;

import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.INSUFFICIENT_PRODUCT_STOCK;
import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.PRODUCT_STOCK_INCONSISTENT;
import static com.sparta.msa.commerce.domain.stock.exception.StockExceptionCode.STOCK_NOT_FOUND;

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
    if (!productStockRepository.existsByProductId(productId)) {
      throw new DomainException(STOCK_NOT_FOUND);
    }

    int affectedRows = productStockRepository.reserve(productId, quantity);
    if (affectedRows == 0) {
      throw new DomainException(INSUFFICIENT_PRODUCT_STOCK);
    }
  }

  @Transactional
  public void confirmSale(Long productId, int quantity) {
    if (productStockRepository.confirmSale(productId, quantity) == 0) {
      throw new DomainException(PRODUCT_STOCK_INCONSISTENT);
    }
  }
}
