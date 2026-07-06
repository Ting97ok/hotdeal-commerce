package com.sparta.msa.commerce.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@Profile("!test")   // 테스트에선 @Scheduled 자동 발사 비활성(스케줄러 빈은 배선 테스트가 프로퍼티로 개별 생성) — 백그라운드 cron 간섭 차단
public class SchedulingConfig {

  // 만료·결제해소 스케줄러가 스레드 1개를 공유하면, 해소가 외부 호출로 장기 점유할 때 만료가 멈춘다.
  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("scheduled-");
    return scheduler;
  }
}
