package com.sparta.msa.commerce.domain.order.repository;

import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

  @Query("""
      SELECT COUNT(o) > 0 FROM Order o
      WHERE o.user = :user
        AND o.hotDeal = :hotDeal
        AND o.status IN ('PENDING', 'PAID')
      """)
  boolean existsActiveOrder(@Param("user") User user, @Param("hotDeal") HotDeal hotDeal);

  @Query("""
      SELECT o FROM Order o
      WHERE o.status = 'PENDING'
        AND o.expiresAt < :now
      """)
  List<Order> findExpiredPending(@Param("now") LocalDateTime now);
}
