package com.sparta.msa.commerce.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.msa.commerce.RedisTestcontainerConfig;
import com.sparta.msa.commerce.domain.auth.facade.AuthFacade;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import com.sparta.msa.commerce.domain.auth.token.RefreshTokenStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestcontainerConfig.class)
@DisplayName("토큰 재발급 (RTR)")
class ReissueIntegrationTest {

  @Autowired
  AuthFacade authFacade;
  @Autowired
  UserRepository userRepository;
  @Autowired
  PasswordEncoder passwordEncoder;
  @Autowired
  RefreshTokenStore refreshTokenStore;

  private Long userId;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    User user = userRepository.save(
        User.create("rtr@example.com", passwordEncoder.encode("password123"), "RTR", UserRole.USER));
    userId = user.getId();
  }

  @Test
  @DisplayName("유효한 refresh로 재발급하면 새 토큰이 나오고 기존 refresh는 무효화된다")
  void reissueRotatesToken() {
    String refresh = UUID.randomUUID().toString();
    refreshTokenStore.save(refresh, userId, 0);

    var result = authFacade.reissue(refresh);

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank().isNotEqualTo(refresh);
    assertThatThrownBy(() -> authFacade.reissue(refresh))
        .isInstanceOf(DomainException.class);
  }

  @Test
  @DisplayName("같은 refresh로 동시에 2번 재발급하면 1번만 성공한다 (GETDEL 원자성)")
  void concurrentReissueOnlyOneSucceeds() throws InterruptedException {
    String refresh = UUID.randomUUID().toString();
    refreshTokenStore.save(refresh, userId, 0);

    int threads = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();

    for (int i = 0; i < threads; i++) {
      executor.submit(() -> {
        ready.countDown();
        try {
          start.await();
          authFacade.reissue(refresh);
          success.incrementAndGet();
        } catch (Exception ignored) {
        }
      });
    }
    ready.await();
    start.countDown();
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    assertThat(success.get()).isEqualTo(1);
  }
}
