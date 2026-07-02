package com.sparta.msa.commerce.domain.payment.service;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

  private final PaymentRepository paymentRepository;

  public Optional<Payment> findById(Long paymentId) {
    return paymentRepository.findById(paymentId);
  }

  public List<Long> findInDoubtIds() {
    return paymentRepository.findInDoubtIds();
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
