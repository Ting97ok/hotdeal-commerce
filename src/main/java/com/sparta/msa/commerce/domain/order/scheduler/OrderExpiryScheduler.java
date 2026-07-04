package com.sparta.msa.commerce.domain.order.scheduler;

import com.sparta.msa.commerce.domain.order.facade.OrderExpiryFacade;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "order.expiry-scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryScheduler {

  private final OrderExpiryFacade orderExpiryFacade;

  @Value("${order.expiry-scheduler.batch-size:500}")
  int batchSize;

  @Scheduled(cron = "0 * * * * *")   // 매분
  public void runExpiry() {
    orderExpiryFacade.expireOverdueOrders(LocalDateTime.now(), batchSize);
  }
}
