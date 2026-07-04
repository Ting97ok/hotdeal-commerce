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
import java.util.List;
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
    List<Payment> targets = paymentService.findInDoubtCreatedBefore(graceThreshold);
    if (targets.isEmpty()) {
      return;
    }
    log.info("IN_DOUBT 해소 시작 — 대상 {}건", targets.size());
    for (Payment payment : targets) {
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
      case PgConfirmResult.Approved approved -> {
        transactionTemplate.executeWithoutResult(status ->
            paymentService.markDone(payment, approved.approvedAt()));
        log.info("IN_DOUBT 해소 — confirm 재시도 승인, DONE 확정(매출 복구). paymentId={}", payment.getId());
      }
      case PgConfirmResult.Rejected rejected -> transactionTemplate.executeWithoutResult(status ->
          failPayment(payment));
      default -> {
      }
    }
  }

  private void apply(Payment payment, PgPayment pgPayment) {
    switch (pgPayment.status()) {
      case DONE -> {
        paymentService.markDone(payment, pgPayment.approvedAt());
        log.info("IN_DOUBT 해소 — 토스 조회 DONE, 확정(매출 복구). paymentId={}", payment.getId());
      }
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
      log.info("IN_DOUBT 해소 — 실패 확정, 주문 CANCELED·핫딜+상품 재고 방출. paymentId={}, orderId={}",
          payment.getId(), order.getId());
    }
  }
}
