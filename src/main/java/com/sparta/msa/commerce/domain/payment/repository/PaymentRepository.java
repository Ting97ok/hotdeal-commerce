package com.sparta.msa.commerce.domain.payment.repository;

import com.sparta.msa.commerce.domain.payment.entity.Payment;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  @Query("SELECT p FROM Payment p WHERE p.status = 'IN_DOUBT' AND p.createdAt < :threshold")
  List<Payment> findInDoubtCreatedBefore(@Param("threshold") LocalDateTime threshold);

  @Modifying
  @Query("UPDATE Payment p SET p.status = 'DONE', p.approvedAt = :approvedAt WHERE p = :payment AND p.status = 'IN_DOUBT'")
  int markDone(@Param("payment") Payment payment, @Param("approvedAt") LocalDateTime approvedAt);

  @Modifying
  @Query("UPDATE Payment p SET p.status = 'FAILED' WHERE p = :payment AND p.status = 'IN_DOUBT'")
  int markFailed(@Param("payment") Payment payment);
}
