package com.sparta.msa.commerce.domain.payment.facade;

import com.sparta.msa.commerce.domain.hotdeal.service.CommonHotDealService;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.dto.request.ConfirmPaymentRequest;
import com.sparta.msa.commerce.domain.payment.dto.response.ConfirmPaymentResponse;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.exception.PaymentExceptionCode;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.mapper.PaymentMapper;
import com.sparta.msa.commerce.domain.payment.service.PaymentService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import com.sparta.msa.commerce.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

  private final CommonOrderService commonOrderService;
  private final CommonHotDealService commonHotDealService;
  private final HotDealStockService hotDealStockService;
  private final ProductStockService productStockService;
  private final PaymentGatewayClient paymentGatewayClient;
  private final PaymentService paymentService;
  private final PaymentMapper paymentMapper;
  private final TransactionTemplate transactionTemplate;

  public ConfirmPaymentResponse confirm(Long userId, ConfirmPaymentRequest request) {
    Order order = transactionTemplate.execute(status -> {
      Order preempted = commonOrderService.getOrderForPayment(request.orderId(), request.amount(), userId);
      commonHotDealService.validateNotCanceledIfHotDeal(preempted.getHotDeal());
      commonOrderService.markPaid(preempted);
      productStockService.confirmSale(preempted.getProductId(), preempted.getQuantity());
      return preempted;
    });

    PgConfirmResult result = paymentGatewayClient.confirm(request.paymentKey(), request.orderId(), request.amount());

    Payment payment = switch (result) {
      case PgConfirmResult.Approved approved -> paymentService.createPayment(order, approved);
      case PgConfirmResult.InDoubt inDoubt -> {
        log.warn("결제 미확정 — IN_DOUBT 보존, 해소 스케줄러가 확정. orderId={}, paymentKey={}",
            order.getId(), request.paymentKey());
        yield paymentService.createInDoubtPayment(order, request.paymentKey());
      }
      case PgConfirmResult.Rejected rejected -> {
        transactionTemplate.executeWithoutResult(status -> failPreemption(order));
        throw new DomainException(PaymentExceptionCode.PAYMENT_REJECTED);
      }
      case PgConfirmResult.GatewayError gatewayError -> {
        transactionTemplate.executeWithoutResult(status -> revertPreemption(order));
        throw new DomainException(PaymentExceptionCode.PAYMENT_GATEWAY_ERROR);
      }
    };

    return paymentMapper.toConfirmResponse(payment);
  }

  private void failPreemption(Order order) {
    if (commonOrderService.markPaymentFailed(order)) {
      hotDealStockService.restore(order.getHotDealId(), order.getQuantity());
      productStockService.restoreSale(order.getProductId(), order.getQuantity());
      log.info("토스 거절 실패 확정 — 주문 CANCELED·핫딜+상품 재고 방출. orderId={}, quantity={}",
          order.getId(), order.getQuantity());
    }
  }

  private void revertPreemption(Order order) {
    if (commonOrderService.markPending(order)) {
      productStockService.restoreSale(order.getProductId(), order.getQuantity());
      log.warn("토스 통신오류 보상 — 주문 PENDING 복귀·상품 재고 복원. orderId={}", order.getId());
    }
  }
}
