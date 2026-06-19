package com.sparta.msa.commerce.domain.stock.service;

import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import com.sparta.msa.commerce.domain.stock.repository.ProductStockRepository;
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
    // TODO(stock-not-found): ProductStock 미존재 시 처리 — 현재 시드 픽스처 전제 orElseThrow()
    ProductStock productStock = productStockRepository.findByProductId(productId).orElseThrow();
    productStock.reserve(quantity);
  }
}
