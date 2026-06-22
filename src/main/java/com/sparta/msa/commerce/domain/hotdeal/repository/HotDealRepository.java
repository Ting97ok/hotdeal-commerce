package com.sparta.msa.commerce.domain.hotdeal.repository;

import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.product.entity.Product;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HotDealRepository extends JpaRepository<HotDeal, Long> {

  @Query("""
      SELECT COUNT(h) > 0 FROM HotDeal h
      WHERE h.product = :product
        AND h.status = 'ACTIVE'
        AND h.startAt < :endAt
        AND :startAt < h.endAt
      """)
  boolean existsOverlappingActiveHotDeal(@Param("product") Product product,
                                         @Param("startAt") LocalDateTime startAt,
                                         @Param("endAt") LocalDateTime endAt);

  @Query("""
      SELECT h FROM HotDeal h
      WHERE h.product = :product
        AND h.status = 'ACTIVE'
        AND h.startAt <= :now
        AND :now < h.endAt
      ORDER BY h.startAt DESC
      LIMIT 1
      """)
  Optional<HotDeal> findActiveByProduct(@Param("product") Product product, @Param("now") LocalDateTime now);
}
