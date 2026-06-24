package com.sparta.msa.commerce.domain.payment.service;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

  private final PaymentRepository paymentRepository;

  @Transactional
  public Payment createPayment(Order order, PgConfirmResult pgResult) {
    return paymentRepository.save(
        Payment.create(order.getId(), pgResult.amount(), pgResult.pgPaymentKey(),
            pgResult.idempotencyKey(), pgResult.approvedAt()));
  }
}
