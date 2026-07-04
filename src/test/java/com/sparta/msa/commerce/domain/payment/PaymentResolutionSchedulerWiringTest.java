package com.sparta.msa.commerce.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

import com.sparta.msa.commerce.domain.payment.facade.PaymentResolutionFacade;
import com.sparta.msa.commerce.domain.payment.scheduler.PaymentResolutionScheduler;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "payment.resolution-scheduler.enabled=true")
@DisplayName("해소 스케줄러 배선")
class PaymentResolutionSchedulerWiringTest {

  @Autowired(required = false)
  PaymentResolutionScheduler paymentResolutionScheduler;

  @MockitoSpyBean
  PaymentResolutionFacade paymentResolutionFacade;

  @Test
  @DisplayName("스케줄러를 활성화하면 PaymentResolutionScheduler 빈이 생성되고 컨텍스트가 로드된다")
  void schedulerBeanLoadsWhenEnabled() {
    assertThat(paymentResolutionScheduler).isNotNull();
  }

  @Test
  @DisplayName("스케줄러 실행은 IN_DOUBT 해소와 PAID 고아 해소를 모두 호출한다")
  void runResolutionTriggersBothScans() {
    paymentResolutionScheduler.runResolution();

    then(paymentResolutionFacade).should().resolveInDoubt(any(LocalDateTime.class));
    then(paymentResolutionFacade).should().resolveOrphanedPaid(any(LocalDateTime.class));
  }
}
