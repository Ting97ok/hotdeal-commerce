package com.sparta.msa.commerce.domain.order.service;

import static com.sparta.msa.commerce.domain.order.exception.OrderExceptionCode.ORDER_NOT_FOUND;
import static com.sparta.msa.commerce.domain.order.exception.OrderExceptionCode.ORDER_STATUS_CONFLICT;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommonOrderService {

  private final OrderRepository orderRepository;

  public Order getOrder(String orderNo) {
    return orderRepository.findByOrderNo(orderNo)
        .orElseThrow(() -> new DomainException(ORDER_NOT_FOUND));
  }

  public Order getOrderById(Long orderId) {
    return orderRepository.findById(orderId)
        .orElseThrow(() -> new DomainException(ORDER_NOT_FOUND));
  }

  public Order getOrderForPayment(String orderNo, BigDecimal paymentAmount, Long userId) {
    Order order = getOrder(orderNo);
    order.validateOwnedBy(userId);
    order.validatePaymentAmount(paymentAmount);
    return order;
  }

  @Transactional
  public void markPaid(Order order) {
    if (orderRepository.markPaid(order) == 0) {
      throw new DomainException(ORDER_STATUS_CONFLICT);
    }
  }

  @Transactional
  public boolean markPending(Order order) {
    return orderRepository.markPending(order) == 1;
  }

  @Transactional
  public boolean markPaymentFailed(Order order) {
    return orderRepository.markPaymentFailed(order) == 1;
  }
}
