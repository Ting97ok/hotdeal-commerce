package com.sparta.msa.commerce.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.domain.auth.dto.request.SignupRequest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("회원가입 API")
class SignupIntegrationTest {

  @Autowired
  MockMvc mockMvc;
  @Autowired
  ObjectMapper objectMapper;
  @Autowired
  UserRepository userRepository;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
  }

  @Nested
  @DisplayName("성공")
  class Success {

    @Test
    @DisplayName("유효한 정보로 회원가입하면 사용자가 저장된다")
    void signupSavesUser() throws Exception {
      SignupRequest request = new SignupRequest("test@example.com", "password123", "테스터");

      mockMvc.perform(post("/api/auth/signup")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value(true));

      assertThat(userRepository.existsByEmail("test@example.com")).isTrue();
    }
  }

  @Nested
  @DisplayName("실패")
  class Fail {

    @Test
    @DisplayName("이미 가입된 이메일이면 409로 실패한다")
    void duplicateEmail() throws Exception {
      userRepository.save(User.create("dup@example.com", "encoded-pw", "기존회원", UserRole.USER));
      SignupRequest request = new SignupRequest("dup@example.com", "password123", "신규회원");

      mockMvc.perform(post("/api/auth/signup")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.result").value(false))
          .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400으로 실패한다")
    void invalidEmail() throws Exception {
      SignupRequest request = new SignupRequest("not-an-email", "password123", "테스터");

      mockMvc.perform(post("/api/auth/signup")
              .contentType(APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
  }
}
