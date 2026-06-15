package com.sparta.msa.commerce.domain.auth.controller;

import com.sparta.msa.commerce.domain.auth.dto.request.LoginRequest;
import com.sparta.msa.commerce.domain.auth.dto.request.LogoutRequest;
import com.sparta.msa.commerce.domain.auth.dto.request.ReissueRequest;
import com.sparta.msa.commerce.domain.auth.dto.request.SignupRequest;
import com.sparta.msa.commerce.domain.auth.dto.response.MeResponse;
import com.sparta.msa.commerce.domain.auth.dto.response.SignupResponse;
import com.sparta.msa.commerce.domain.auth.dto.response.TokenResponse;
import com.sparta.msa.commerce.domain.auth.facade.AuthFacade;
import com.sparta.msa.commerce.global.security.AuthUser;
import com.sparta.msa.commerce.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthFacade authFacade;

  @PostMapping("/signup")
  public SignupResponse signup(@RequestBody @Valid SignupRequest request) {
    return authFacade.signup(request);
  }

  @PostMapping("/login")
  public TokenResponse login(@RequestBody @Valid LoginRequest request) {
    return authFacade.login(request);
  }

  @PostMapping("/reissue")
  public TokenResponse reissue(@RequestBody @Valid ReissueRequest request) {
    return authFacade.reissue(request.refreshToken());
  }

  @PostMapping("/logout")
  public void logout(@RequestBody @Valid LogoutRequest request) {
    authFacade.logout(request.refreshToken());
  }

  @PostMapping("/logout-all")
  public void logoutAll(@CurrentUser AuthUser authUser) {
    authFacade.logoutAll(authUser.userId());
  }

  @GetMapping("/me")
  public MeResponse me(@CurrentUser AuthUser authUser) {
    return authFacade.getMe(authUser.userId());
  }
}
