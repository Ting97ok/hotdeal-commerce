package com.sparta.msa.commerce.domain.payment.scheduler;

import com.sparta.msa.commerce.domain.payment.facade.PaymentResolutionFacade;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.resolution-scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentResolutionScheduler {

  private final PaymentResolutionFacade paymentResolutionFacade;

  @Scheduled(cron = "0 * * * * *")   // 매분
  public void runResolution() {
    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now());
  }
}
