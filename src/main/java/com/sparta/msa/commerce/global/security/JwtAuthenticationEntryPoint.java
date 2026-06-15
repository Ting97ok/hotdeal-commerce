package com.sparta.msa.commerce.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.msa.commerce.domain.auth.exception.AuthExceptionCode;
import com.sparta.msa.commerce.global.exception.ExceptionCode;
import com.sparta.msa.commerce.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException authException) throws IOException {
    ExceptionCode code = resolve(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE));

    response.setStatus(code.getStatus().value());
    response.setContentType("application/json;charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ApiResponse.failBody(code.name(), code.getMessage()));
  }

  private ExceptionCode resolve(Object attribute) {
    if (attribute instanceof ExceptionCode exceptionCode) {
      return exceptionCode;
    }
    return AuthExceptionCode.TOKEN_NOT_FOUND;
  }
}
