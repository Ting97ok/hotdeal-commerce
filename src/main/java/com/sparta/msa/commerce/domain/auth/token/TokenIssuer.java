package com.sparta.msa.commerce.domain.auth.token;

import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.global.security.JwtKeyHolder;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuer {

  private static final String ROLES_CLAIM = "roles";

  private final JwtKeyHolder keyHolder;
  private final long expirationMinutes;
  private final String issuer;

  public TokenIssuer(
      JwtKeyHolder keyHolder,
      @Value("${jwt.token.exp-minute-time}") long expirationMinutes,
      @Value("${jwt.token.issuer}") String issuer) {
    this.keyHolder = keyHolder;
    this.expirationMinutes = expirationMinutes;
    this.issuer = issuer;
  }

  public String createAccessToken(Long userId, UserRole role) {
    Instant now = Instant.now();
    Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);

    Claims claims = Jwts.claims().setSubject(String.valueOf(userId));
    claims.put(ROLES_CLAIM, List.of("ROLE_" + role.name()));

    return Jwts.builder()
        .setClaims(claims)
        .setIssuer(issuer)
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(expiry))
        .signWith(keyHolder.getKey())
        .compact();
  }
}
