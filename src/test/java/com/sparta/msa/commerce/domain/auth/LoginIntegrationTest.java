package com.sparta.msa.commerce.domain.auth;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.RedisTestcontainerConfig;
import com.sparta.msa.commerce.domain.auth.dto.request.LoginRequest;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestcontainerConfig.class)
@DisplayName("로그인 API")
class LoginIntegrationTest {

  @Autowired
  MockMvc mockMvc;
  @Autowired
  ObjectMapper objectMapper;
  @Autowired
  UserRepository userRepository;
  @Autowired
  PasswordEncoder passwordEncoder;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    userRepository.save(
        User.create("login@example.com", passwordEncoder.encode("password123"), "로그인", UserRole.USER));
  }

  @Nested
  @DisplayName("성공")
  class Success {

    @Test
    @DisplayName("올바른 이메일/비밀번호로 로그인하면 토큰이 발급된다")
    void loginIssuesTokens() throws Exception {
      LoginRequest request = new LoginRequest("login@example.com", "password123");

      mockMvc.perform(post("/api/auth/login")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value(true))
          .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
          .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }
  }

  @Nested
  @DisplayName("실패")
  class Fail {

    @Test
    @DisplayName("비밀번호가 틀리면 401로 실패한다")
    void wrongPassword() throws Exception {
      LoginRequest request = new LoginRequest("login@example.com", "wrong-password");

      mockMvc.perform(post("/api/auth/login")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }
  }
}
