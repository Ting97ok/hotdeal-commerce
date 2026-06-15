package com.sparta.msa.commerce.global.security;

import com.sparta.msa.commerce.global.exception.DomainException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  public static final String AUTH_ERROR_ATTRIBUTE = "authErrorCode";
  private static final String BEARER_PREFIX = "Bearer ";

  private final TokenVerifier tokenVerifier;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String token = resolveToken(request);
    if (token != null) {
      try {
        AuthUser authUser = tokenVerifier.parse(token);
        var authentication = new UsernamePasswordAuthenticationToken(
            authUser, null, List.of(new SimpleGrantedAuthority(authUser.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (DomainException e) {
        SecurityContextHolder.clearContext();
        request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getExceptionCode());
      }
    }
    chain.doFilter(request, response);
  }

  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}
