package com.sparta.msa.commerce.domain.auth.facade;

import com.sparta.msa.commerce.domain.auth.dto.request.LoginRequest;
import com.sparta.msa.commerce.domain.auth.dto.request.SignupRequest;
import com.sparta.msa.commerce.domain.auth.dto.response.MeResponse;
import com.sparta.msa.commerce.domain.auth.dto.response.SignupResponse;
import com.sparta.msa.commerce.domain.auth.dto.response.TokenResponse;
import com.sparta.msa.commerce.domain.auth.exception.AuthExceptionCode;
import com.sparta.msa.commerce.domain.auth.service.AuthService;
import com.sparta.msa.commerce.domain.auth.token.RefreshTokenInfo;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.service.UserService;
import com.sparta.msa.commerce.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthFacade {

  private final UserService userService;
  private final AuthService authService;

  @Transactional
  public SignupResponse signup(SignupRequest request) {
    User user = userService.register(request.email(), request.password(), request.name());
    return new SignupResponse(user.getId());
  }

  @Transactional(readOnly = true)
  public TokenResponse login(LoginRequest request) {
    User user = userService.findByEmail(request.email())
        .orElseThrow(() -> new DomainException(AuthExceptionCode.INVALID_CREDENTIALS));
    return authService.authenticate(user, request.password());
  }

  @Transactional(readOnly = true)
  public TokenResponse reissue(String refreshToken) {
    RefreshTokenInfo info = authService.consumeRefreshToken(refreshToken);
    User user = userService.getUser(info.userId());
    return authService.reissueFor(user, info.tokenVersion());
  }

  public void logout(String refreshToken) {
    authService.logout(refreshToken);
  }

  @Transactional
  public void logoutAll(Long userId) {
    userService.incrementTokenVersion(userId);
  }

  @Transactional(readOnly = true)
  public MeResponse getMe(Long userId) {
    User user = userService.getUser(userId);
    return MeResponse.from(user);
  }
}
