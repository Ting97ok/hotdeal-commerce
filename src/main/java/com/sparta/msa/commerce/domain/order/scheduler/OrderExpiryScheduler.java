package com.sparta.msa.commerce.domain.order.scheduler;

import com.sparta.msa.commerce.domain.order.facade.OrderExpiryFacade;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "order.expiry-scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryScheduler {

  private static final int EXPIRY_BATCH_SIZE = 500;

  private final OrderExpiryFacade orderExpiryFacade;

  @Scheduled(cron = "0 * * * * *")   // 매분
  public void runExpiry() {
    orderExpiryFacade.expireOverdueOrders(LocalDateTime.now(), EXPIRY_BATCH_SIZE);
  }
}
