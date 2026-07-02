package com.sparta.msa.commerce.domain.payment.facade;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgPayment;
import com.sparta.msa.commerce.domain.payment.service.PaymentService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PaymentResolutionFacade {

  private final PaymentService paymentService;
  private final PaymentGatewayClient paymentGatewayClient;
  private final CommonOrderService commonOrderService;
  private final HotDealStockService hotDealStockService;
  private final ProductStockService productStockService;
  private final TransactionTemplate transactionTemplate;

  public void resolveInDoubt(LocalDateTime now) {
    LocalDateTime graceThreshold = now.minusMinutes(1);
    for (Payment payment : paymentService.findInDoubtCreatedBefore(graceThreshold)) {
      resolveOne(payment);
    }
  }

  private void resolveOne(Payment payment) {
    PgPayment pgPayment = paymentGatewayClient.getPayment(payment.getPgPaymentKey());
    transactionTemplate.executeWithoutResult(status -> apply(payment, pgPayment));
  }

  private void apply(Payment payment, PgPayment pgPayment) {
    switch (pgPayment.status()) {
      case DONE -> paymentService.markDone(payment, pgPayment.approvedAt());
      case EXPIRED, ABORTED, CANCELED -> failPayment(payment);
      default -> {
      }
    }
  }

  private void failPayment(Payment payment) {
    if (!paymentService.markFailed(payment)) {
      return;
    }
    Order order = commonOrderService.getOrderById(payment.getOrderId());
    if (commonOrderService.markPaymentFailed(order)) {
      hotDealStockService.restore(order.getHotDealId(), order.getQuantity());
      productStockService.restoreSale(order.getProductId(), order.getQuantity());
    }
  }
}
