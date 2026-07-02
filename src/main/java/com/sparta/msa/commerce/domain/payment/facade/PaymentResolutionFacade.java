package com.sparta.msa.commerce.domain.payment.facade;

import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgPayment;
import com.sparta.msa.commerce.domain.payment.gateway.PgPaymentStatus;
import com.sparta.msa.commerce.domain.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentResolutionFacade {

  private final PaymentGatewayClient paymentGatewayClient;
  private final PaymentService paymentService;

  @Transactional
  public void resolve(Payment payment) {
    PgPayment pgPayment = paymentGatewayClient.getPayment(payment.getPgPaymentKey());
    if (pgPayment.status() == PgPaymentStatus.DONE) {
      paymentService.markDone(payment, pgPayment.approvedAt());
    }
  }
}
