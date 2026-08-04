package com.sparta.msa.commerce.domain.payment.facade;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.client.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.client.dto.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.client.dto.PgPayment;
import com.sparta.msa.commerce.domain.payment.client.dto.PgPaymentStatus;
import com.sparta.msa.commerce.domain.payment.service.PaymentService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResolutionFacade {

  private static final int IN_DOUBT_GRACE_MINUTES = 1;
  private static final int ORPHAN_GRACE_MINUTES = 5;
  private static final int RESOLUTION_BATCH_SIZE = 500;

  private final PaymentService paymentService;
  private final PaymentGatewayClient paymentGatewayClient;
  private final CommonOrderService commonOrderService;
  private final HotDealStockService hotDealStockService;
  private final ProductStockService productStockService;
  private final TransactionTemplate transactionTemplate;

  public void resolveInDoubt(LocalDateTime now) {
    LocalDateTime graceThreshold = now.minusMinutes(IN_DOUBT_GRACE_MINUTES);
    List<Payment> targets = paymentService.findInDoubtCreatedBefore(graceThreshold, RESOLUTION_BATCH_SIZE);
    if (targets.isEmpty()) {
      return;
    }
    log.info("IN_DOUBT 해소 시작 — 대상 {}건", targets.size());
    for (Payment payment : targets) {
      resolveOne(payment);
    }
  }

  private void resolveOne(Payment payment) {
    try {
      Optional<PgPayment> pgPayment = paymentGatewayClient.findPayment(payment.getPgPaymentKey());
      if (pgPayment.isEmpty()) {
        transactionTemplate.executeWithoutResult(status -> failPayment(payment));   // 토스에 결제 없음(위조 키 등) — 실패 확정
        return;
      }
      if (pgPayment.get().status() == PgPaymentStatus.IN_PROGRESS) {
        retryConfirm(payment);
        return;
      }
      transactionTemplate.executeWithoutResult(status -> apply(payment, pgPayment.get()));
    } catch (RuntimeException e) {
      log.warn("IN_DOUBT 해소 실패 — 다음 회차 재시도. paymentId={}, pgPaymentKey={}",
          payment.getId(), payment.getPgPaymentKey(), e);
    }
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

  public void resolveOrphanedPaid(LocalDateTime now) {
    List<Order> orphans = paymentService.findPaidOrdersWithoutPayment(
        now.minusMinutes(ORPHAN_GRACE_MINUTES), RESOLUTION_BATCH_SIZE);
    if (orphans.isEmpty()) {
      return;
    }
    log.info("PAID 고아 해소 시작 — 대상 {}건", orphans.size());
    for (Order order : orphans) {
      resolveOrphan(order);
    }
  }

  private void resolveOrphan(Order order) {
    try {
      Optional<PgPayment> pgPayment = paymentGatewayClient.findPaymentByOrderId(order.getOrderNo());
      if (pgPayment.isEmpty()) {
        failOrphan(order, "토스에 결제 없음(매입 미발생)");
        return;
      }
      switch (pgPayment.get().status()) {
        case DONE -> recoverOrphan(order, pgPayment.get());
        case EXPIRED, ABORTED, CANCELED -> failOrphan(order, "토스 실패 상태 " + pgPayment.get().status());
        default -> {
        }
      }
    } catch (RuntimeException e) {
      log.warn("PAID 고아 해소 실패 — 다음 회차 재시도. orderId={}", order.getId(), e);
    }
  }

  private void recoverOrphan(Order order, PgPayment pgPayment) {
    if (order.getOrderAmount().compareTo(pgPayment.totalAmount()) != 0) {
      log.warn("PAID 고아 금액 불일치 — 자동 확정 보류(수동 확인 필요). orderId={}, 주문금액={}, 토스금액={}",
          order.getId(), order.getOrderAmount(), pgPayment.totalAmount());
      return;
    }
    paymentService.createPayment(order, new PgConfirmResult.Approved(
        pgPayment.paymentKey(), pgPayment.totalAmount(), pgPayment.approvedAt()));
    log.info("PAID 고아 매출 복구 — Payment DONE 생성. orderId={}, paymentKey={}",
        order.getId(), pgPayment.paymentKey());
  }

  private void failOrphan(Order order, String reason) {
    transactionTemplate.executeWithoutResult(status -> {
      if (commonOrderService.markPaymentFailed(order)) {
        hotDealStockService.restore(order.getHotDealId(), order.getQuantity());
        productStockService.restoreSale(order.getProductId(), order.getQuantity());
        log.info("PAID 고아 실패 확정 — 주문 CANCELED·핫딜+상품 재고 방출. orderId={}, 사유={}",
            order.getId(), reason);
      }
    });
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
