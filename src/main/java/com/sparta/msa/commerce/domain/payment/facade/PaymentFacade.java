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
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import com.sparta.msa.commerce.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

  private final CommonOrderService commonOrderService;
  private final CommonHotDealService commonHotDealService;
  private final ProductStockService productStockService;
  private final PaymentGatewayClient paymentGatewayClient;
  private final PaymentService paymentService;
  private final PaymentMapper paymentMapper;
  private final TransactionTemplate transactionTemplate;

  public ConfirmPaymentResponse confirm(ConfirmPaymentRequest request) {
    Order order = preempt(request);

    PgConfirmResult result;
    try {
      result = paymentGatewayClient.confirm(request.paymentKey(), request.orderId(), request.amount());
    } catch (DomainException e) {
      compensate(order);
      throw e;
    }

    return switch (result) {
      case PgConfirmResult.Approved approved -> settleApproved(order, approved);
      case PgConfirmResult.InDoubt inDoubt -> settleInDoubt(order);
      case PgConfirmResult.Rejected rejected -> {
        compensate(order);
        throw new DomainException(PaymentExceptionCode.PAYMENT_REJECTED);
      }
    };
  }

  private Order preempt(ConfirmPaymentRequest request) {
    return transactionTemplate.execute(status -> {
      Order order = commonOrderService.getOrderForPayment(request.orderId(), request.amount());
      commonHotDealService.validateNotCanceledIfHotDeal(order.getHotDeal());
      commonOrderService.markPaid(order);
      productStockService.confirmSale(order.getProductId(), order.getQuantity());
      return order;
    });
  }

  private ConfirmPaymentResponse settleApproved(Order order, PgConfirmResult.Approved approved) {
    return transactionTemplate.execute(status -> {
      Payment payment = paymentService.createPayment(order, approved);
      return paymentMapper.toConfirmResponse(payment);
    });
  }

  private ConfirmPaymentResponse settleInDoubt(Order order) {
    return transactionTemplate.execute(status -> {
      Payment payment = paymentService.createInDoubtPayment(order);
      return paymentMapper.toConfirmResponse(payment);
    });
  }

  private void compensate(Order order) {
    transactionTemplate.executeWithoutResult(status -> {
      commonOrderService.markPending(order);
      productStockService.restoreSale(order.getProductId(), order.getQuantity());
    });
  }
}
