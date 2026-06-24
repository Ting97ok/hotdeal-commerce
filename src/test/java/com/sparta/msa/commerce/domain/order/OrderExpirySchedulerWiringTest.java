package com.sparta.msa.commerce.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.msa.commerce.domain.order.scheduler.OrderExpiryScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "order.expiry-scheduler.enabled=true")
@DisplayName("만료 스케줄러 배선")
class OrderExpirySchedulerWiringTest {

  @Autowired(required = false)
  OrderExpiryScheduler orderExpiryScheduler;

  @Test
  @DisplayName("스케줄러를 활성화하면 OrderExpiryScheduler 빈이 생성되고 컨텍스트가 로드된다")
  void schedulerBeanLoadsWhenEnabled() {
    assertThat(orderExpiryScheduler).isNotNull();
  }
}
