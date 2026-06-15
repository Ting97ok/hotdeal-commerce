package com.sparta.msa.commerce.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.msa.commerce.domain.auth.token.TokenIssuer;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("보호 API / 인가")
class ProtectedApiIntegrationTest {

  @Autowired
  MockMvc mockMvc;
  @Autowired
  UserRepository userRepository;
  @Autowired
  PasswordEncoder passwordEncoder;
  @Autowired
  TokenIssuer tokenIssuer;

  @Value("${jwt.token.secret-key}")
  String secretKey;
  @Value("${jwt.token.issuer}")
  String issuer;

  private Long userId;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    User user = userRepository.save(
        User.create("me@example.com", passwordEncoder.encode("password123"), "미유저", UserRole.USER));
    userId = user.getId();
  }

  @Nested
  @DisplayName("인증")
  class Authentication {

    @Test
    @DisplayName("유효한 토큰으로 내 정보를 조회한다")
    void meWithValidToken() throws Exception {
      String token = tokenIssuer.createAccessToken(userId, UserRole.USER);

      mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value(true))
          .andExpect(jsonPath("$.data.userId").value(userId))
          .andExpect(jsonPath("$.data.email").value("me@example.com"));
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void meWithoutToken() throws Exception {
      mockMvc.perform(get("/api/auth/me"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("TOKEN_NOT_FOUND"));
    }

    @Test
    @DisplayName("변조된 토큰이면 401")
    void meWithInvalidToken() throws Exception {
      mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.value"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("만료된 토큰이면 401")
    void meWithExpiredToken() throws Exception {
      String token = signedToken(issuer, Instant.now().minus(1, ChronoUnit.HOURS));

      mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("EXPIRED_TOKEN"));
    }

    @Test
    @DisplayName("issuer가 다른 토큰이면 401")
    void meWithWrongIssuer() throws Exception {
      String token = signedToken("evil-issuer", Instant.now().plus(1, ChronoUnit.HOURS));

      mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("서명 없는(alg:none) 토큰이면 401")
    void meWithUnsignedToken() throws Exception {
      String token = unsignedToken();

      mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }
  }

  @Nested
  @DisplayName("인가")
  class Authorization {

    @Test
    @DisplayName("USER 토큰으로 admin 경로 접근 시 403")
    void userCannotAccessAdmin() throws Exception {
      String token = tokenIssuer.createAccessToken(userId, UserRole.USER);

      mockMvc.perform(get("/api/admin/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
  }

  private String signedToken(String issuer, Instant expiry) {
    SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    return Jwts.builder()
        .setSubject(String.valueOf(userId))
        .claim("roles", List.of("ROLE_USER"))
        .setIssuer(issuer)
        .setIssuedAt(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
        .setExpiration(Date.from(expiry))
        .signWith(key)
        .compact();
  }

  private String unsignedToken() {
    return Jwts.builder()
        .setSubject(String.valueOf(userId))
        .claim("roles", List.of("ROLE_USER"))
        .setIssuer(issuer)
        .setExpiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
        .compact();
  }
}
