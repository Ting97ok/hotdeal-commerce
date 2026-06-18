package com.sparta.msa.commerce.domain.product.service;

import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

  private final ProductRepository productRepository;

  public Product getProduct(Long productId) {
    // TODO(product-not-found): 미존재 시 PRODUCT_NOT_FOUND(404) 던지기 — 현재 임시 orElseThrow()
    return productRepository.findById(productId).orElseThrow();
  }
}
