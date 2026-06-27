package com.sparta.msa.commerce.domain.stock.repository;

import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HotDealStockRepository extends JpaRepository<HotDealStock, Long> {

  Optional<HotDealStock> findByHotDealId(Long hotDealId);

  @Modifying
  @Query("""
      UPDATE HotDealStock hs SET hs.remainingQuantity = hs.remainingQuantity - :quantity
      WHERE hs.hotDealId = :hotDealId AND hs.remainingQuantity >= :quantity
      """)
  int deductConditional(@Param("hotDealId") Long hotDealId, @Param("quantity") int quantity);

  @Modifying
  @Query("""
      UPDATE HotDealStock hs SET hs.remainingQuantity = hs.remainingQuantity + :quantity
      WHERE hs.hotDealId = :hotDealId
      """)
  int restoreConditional(@Param("hotDealId") Long hotDealId, @Param("quantity") int quantity);
}
