package com.sparta.msa.commerce.domain.stock.repository;

import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

  Optional<ProductStock> findByProductId(Long productId);
}
