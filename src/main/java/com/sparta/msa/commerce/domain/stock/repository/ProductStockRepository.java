package com.sparta.msa.commerce.domain.stock.repository;

import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

  Optional<ProductStock> findByProductId(Long productId);

  boolean existsByProductId(Long productId);

  @Modifying
  @Query("""
      UPDATE ProductStock ps
         SET ps.reservedQuantity = ps.reservedQuantity + :quantity
       WHERE ps.productId = :productId
         AND ps.onHandQuantity - ps.reservedQuantity >= :quantity
  """)
  int reserve(@Param("productId") Long productId, @Param("quantity") int quantity);
}
