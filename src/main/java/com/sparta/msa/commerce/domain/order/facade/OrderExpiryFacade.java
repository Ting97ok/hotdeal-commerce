package com.sparta.msa.commerce.domain.order.facade;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderExpiryFacade {

  private final OrderRepository orderRepository;
  private final HotDealStockService hotDealStockService;

  @Transactional
  public void expireOverdueOrders(LocalDateTime now) {
    List<Order> overdueOrders = orderRepository.findExpiredPending(now);
    for (Order order : overdueOrders) {
      if (orderRepository.markExpired(order) == 1) {
        hotDealStockService.restore(order.getHotDealId(), order.getQuantity());
      }
    }
  }
}
