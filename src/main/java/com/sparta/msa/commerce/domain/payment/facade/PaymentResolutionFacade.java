package com.sparta.msa.commerce.domain.payment.facade;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.gateway.PgPayment;
import com.sparta.msa.commerce.domain.payment.gateway.PgPaymentStatus;
import com.sparta.msa.commerce.domain.payment.service.PaymentService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
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
      try {
        resolveOne(payment);
      } catch (RuntimeException e) {
        log.warn("IN_DOUBT 해소 실패 — 다음 회차 재시도. paymentId={}, pgPaymentKey={}",
            payment.getId(), payment.getPgPaymentKey(), e);
      }
    }
  }

  private void resolveOne(Payment payment) {
    PgPayment pgPayment = paymentGatewayClient.getPayment(payment.getPgPaymentKey());
    if (pgPayment.status() == PgPaymentStatus.IN_PROGRESS) {
      retryConfirm(payment);
      return;
    }
    transactionTemplate.executeWithoutResult(status -> apply(payment, pgPayment));
  }

  private void retryConfirm(Payment payment) {
    Order order = commonOrderService.getOrderById(payment.getOrderId());
    PgConfirmResult result = paymentGatewayClient.confirm(
        payment.getPgPaymentKey(), order.getOrderNo(), payment.getAmount());
    switch (result) {
      case PgConfirmResult.Approved approved -> transactionTemplate.executeWithoutResult(status ->
          paymentService.markDone(payment, approved.approvedAt()));
      case PgConfirmResult.Rejected rejected -> transactionTemplate.executeWithoutResult(status ->
          failPayment(payment));
      default -> {
      }
    }
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
