package com.sparta.msa.commerce.domain.payment.service;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.client.dto.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

  private final PaymentRepository paymentRepository;

  public List<Payment> findInDoubtCreatedBefore(LocalDateTime threshold, int limit) {
    return paymentRepository.findInDoubtCreatedBefore(threshold, Limit.of(limit));
  }

  public List<Order> findPaidOrdersWithoutPayment(LocalDateTime threshold, int limit) {
    return paymentRepository.findPaidOrdersWithoutPayment(threshold, Limit.of(limit));
  }

  @Transactional
  public Payment createPayment(Order order, PgConfirmResult.Approved approved) {
    return paymentRepository.save(Payment.create(order, approved));
  }

  @Transactional
  public Payment createInDoubtPayment(Order order, String paymentKey) {
    return paymentRepository.save(Payment.createInDoubt(order, paymentKey));
  }

  @Transactional
  public void markDone(Payment payment, LocalDateTime approvedAt) {
    paymentRepository.markDone(payment, approvedAt);
  }

  @Transactional
  public boolean markFailed(Payment payment) {
    return paymentRepository.markFailed(payment) == 1;
  }
}
