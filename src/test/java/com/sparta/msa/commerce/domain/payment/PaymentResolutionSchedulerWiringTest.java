package com.sparta.msa.commerce.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.msa.commerce.domain.payment.scheduler.PaymentResolutionScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "payment.resolution-scheduler.enabled=true")
@DisplayName("해소 스케줄러 배선")
class PaymentResolutionSchedulerWiringTest {

  @Autowired(required = false)
  PaymentResolutionScheduler paymentResolutionScheduler;

  @Test
  @DisplayName("스케줄러를 활성화하면 PaymentResolutionScheduler 빈이 생성되고 컨텍스트가 로드된다")
  void schedulerBeanLoadsWhenEnabled() {
    assertThat(paymentResolutionScheduler).isNotNull();
  }
}
