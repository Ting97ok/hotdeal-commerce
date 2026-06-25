package com.sparta.msa.commerce.domain.payment.facade;

import com.sparta.msa.commerce.domain.hotdeal.service.CommonHotDealService;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.dto.request.ConfirmPaymentRequest;
import com.sparta.msa.commerce.domain.payment.dto.response.ConfirmPaymentResponse;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.mapper.PaymentMapper;
import com.sparta.msa.commerce.domain.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

  private final CommonOrderService commonOrderService;
  private final CommonHotDealService commonHotDealService;
  private final PaymentGatewayClient paymentGatewayClient;
  private final PaymentService paymentService;
  private final PaymentMapper paymentMapper;

  @Transactional
  public ConfirmPaymentResponse confirm(ConfirmPaymentRequest request) {
    Order order = commonOrderService.getOrderForPayment(request.orderId(), request.amount());
    commonHotDealService.validateNotCanceledIfHotDeal(order.getHotDeal());
    commonOrderService.markPaid(order);
    PgConfirmResult pgResult = paymentGatewayClient.confirm(request.paymentKey(), request.orderId(), request.amount());
    Payment payment = paymentService.createPayment(order, pgResult);
    return paymentMapper.toConfirmResponse(payment);
  }
}
