package com.sparta.msa.commerce.domain.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.RedisTestcontainerConfig;
import com.sparta.msa.commerce.domain.auth.dto.request.LogoutRequest;
import com.sparta.msa.commerce.domain.auth.facade.AuthFacade;
import com.sparta.msa.commerce.domain.auth.token.RefreshTokenStore;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestcontainerConfig.class)
@DisplayName("로그아웃")
class LogoutIntegrationTest {

  @Autowired
  MockMvc mockMvc;
  @Autowired
  ObjectMapper objectMapper;
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
        User.create("logout@example.com", passwordEncoder.encode("password123"), "로그아웃", UserRole.USER));
    userId = user.getId();
  }

  @Test
  @DisplayName("access 토큰 없이도 refresh로 로그아웃할 수 있다")
  void logoutWithoutAccessToken() throws Exception {
    String refresh = UUID.randomUUID().toString();
    refreshTokenStore.save(refresh, userId, 0);

    mockMvc.perform(post("/api/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LogoutRequest(refresh))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("로그아웃하면 해당 refresh 토큰으로 재발급할 수 없다")
  void logoutInvalidatesRefreshToken() {
    String refresh = UUID.randomUUID().toString();
    refreshTokenStore.save(refresh, userId, 0);

    authFacade.logout(refresh);

    assertThatThrownBy(() -> authFacade.reissue(refresh))
        .isInstanceOf(DomainException.class)
        .extracting("code").isEqualTo("REFRESH_TOKEN_NOT_FOUND");
  }

  @Test
  @DisplayName("전체 로그아웃하면 기존 refresh 토큰이 tokenVersion 불일치로 거부된다")
  void logoutAllRevokesByTokenVersion() {
    String refresh = UUID.randomUUID().toString();
    refreshTokenStore.save(refresh, userId, 0);

    authFacade.logoutAll(userId);

    assertThatThrownBy(() -> authFacade.reissue(refresh))
        .isInstanceOf(DomainException.class)
        .extracting("code").isEqualTo("TOKEN_REVOKED");
  }
}
