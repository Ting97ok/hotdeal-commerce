package com.sparta.msa.commerce.domain.product.service;

import static com.sparta.msa.commerce.domain.product.exception.ProductExceptionCode.PRODUCT_NOT_FOUND;

import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

  private final ProductRepository productRepository;

  public Product getProduct(Long productId) {
    return productRepository.findById(productId)
        .orElseThrow(() -> new DomainException(PRODUCT_NOT_FOUND));
  }
}
