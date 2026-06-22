package com.sparta.msa.commerce.domain.order.service;

import static com.sparta.msa.commerce.domain.order.exception.OrderExceptionCode.ALREADY_PURCHASED;

import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.order.config.OrderProperties;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

  private final OrderRepository orderRepository;
  private final OrderProperties orderProperties;

  @Transactional
  public Order create(User user, HotDeal hotDeal, Product product, int quantity) {
    validateNotAlreadyPurchased(user, hotDeal);
    Order order = Order.create(user, hotDeal, product, quantity, orderProperties.paymentTimeout());
    return orderRepository.save(order);
  }

  private void validateNotAlreadyPurchased(User user, HotDeal hotDeal) {
    if (orderRepository.existsActiveOrder(user, hotDeal)) {
      throw new DomainException(ALREADY_PURCHASED);
    }
  }
}
