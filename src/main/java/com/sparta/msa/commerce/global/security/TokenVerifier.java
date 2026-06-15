package com.sparta.msa.commerce.global.security;

import com.sparta.msa.commerce.domain.auth.exception.AuthExceptionCode;
import com.sparta.msa.commerce.global.exception.DomainException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenVerifier {

  private static final String ROLES_CLAIM = "roles";
  private static final long CLOCK_SKEW_SECONDS = 30;

  private final JwtKeyHolder keyHolder;
  private final String issuer;

  public TokenVerifier(JwtKeyHolder keyHolder, @Value("${jwt.token.issuer}") String issuer) {
    this.keyHolder = keyHolder;
    this.issuer = issuer;
  }

  public AuthUser parse(String token) {
    try {
      Claims claims = Jwts.parserBuilder()
          .setSigningKey(keyHolder.getKey())
          .requireIssuer(issuer)
          .setAllowedClockSkewSeconds(CLOCK_SKEW_SECONDS)
          .build()
          .parseClaimsJws(token)
          .getBody();
      Long userId = Long.parseLong(claims.getSubject());
      List<?> roles = claims.get(ROLES_CLAIM, List.class);
      String role = (roles == null || roles.isEmpty()) ? "ROLE_USER" : String.valueOf(roles.getFirst());
      return new AuthUser(userId, role);
    } catch (ExpiredJwtException e) {
      throw new DomainException(AuthExceptionCode.EXPIRED_TOKEN);
    } catch (JwtException | IllegalArgumentException e) {
      throw new DomainException(AuthExceptionCode.INVALID_TOKEN);
    }
  }
}
