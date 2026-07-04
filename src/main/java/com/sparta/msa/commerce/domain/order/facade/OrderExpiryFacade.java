package com.sparta.msa.commerce.domain.order.facade;

import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpiryFacade {

  private final CommonOrderService commonOrderService;
  private final HotDealStockService hotDealStockService;
  private final TransactionTemplate transactionTemplate;

  public void expireOverdueOrders(LocalDateTime now, int limit) {
    List<Order> overdueOrders = commonOrderService.findExpiredPending(now, limit);
    if (overdueOrders.isEmpty()) {
      return;
    }
    log.info("미결제 만료 처리 시작 — 대상 {}건", overdueOrders.size());
    for (Order order : overdueOrders) {
      expireOne(order);
    }
  }

  private void expireOne(Order order) {
    try {
      transactionTemplate.executeWithoutResult(status -> {
        if (commonOrderService.markExpired(order)) {
          hotDealStockService.restore(order.getHotDealId(), order.getQuantity());
        }
      });
    } catch (RuntimeException e) {
      log.warn("만료 처리 실패 — 다음 회차 재시도. orderId={}", order.getId(), e);
    }
  }
}
