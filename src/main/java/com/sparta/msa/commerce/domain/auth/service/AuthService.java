package com.sparta.msa.commerce.domain.auth.service;

import com.sparta.msa.commerce.domain.auth.dto.response.TokenResponse;
import com.sparta.msa.commerce.domain.auth.exception.AuthExceptionCode;
import com.sparta.msa.commerce.domain.auth.token.RefreshTokenInfo;
import com.sparta.msa.commerce.domain.auth.token.RefreshTokenStore;
import com.sparta.msa.commerce.domain.auth.token.TokenIssuer;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.global.exception.DomainException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final PasswordEncoder passwordEncoder;
  private final TokenIssuer tokenIssuer;
  private final RefreshTokenStore refreshTokenStore;

  public TokenResponse authenticate(User user, String rawPassword) {
    if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
      throw new DomainException(AuthExceptionCode.INVALID_CREDENTIALS);
    }
    return issueTokenPair(user);
  }

  // GETDEL(원자) 후 검증 순서 — 버전 불일치 refresh는 이미 무효화된 것이라 소비(삭제)돼도 손실 없음
  public RefreshTokenInfo consumeRefreshToken(String refreshToken) {
    return refreshTokenStore.getAndDelete(refreshToken)
        .orElseThrow(() -> new DomainException(AuthExceptionCode.REFRESH_TOKEN_NOT_FOUND));
  }

  public TokenResponse reissueFor(User user, int tokenVersion) {
    if (!user.matchesTokenVersion(tokenVersion)) {
      throw new DomainException(AuthExceptionCode.TOKEN_REVOKED);
    }
    return issueTokenPair(user);
  }

  public void logout(String refreshToken) {
    refreshTokenStore.delete(refreshToken);
  }

  private TokenResponse issueTokenPair(User user) {
    String accessToken = tokenIssuer.createAccessToken(user.getId(), user.getRole());
    String refreshToken = UUID.randomUUID().toString();
    refreshTokenStore.save(refreshToken, user.getId(), user.getTokenVersion());
    return new TokenResponse(accessToken, refreshToken);
  }
}
