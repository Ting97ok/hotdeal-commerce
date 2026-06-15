package com.sparta.msa.commerce.global.security;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtKeyHolder {

  private static final int MIN_SECRET_KEY_BYTES = 32;

  private final SecretKey key;

  public JwtKeyHolder(@Value("${jwt.token.secret-key}") String secretKey) {
    byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length < MIN_SECRET_KEY_BYTES) {
      throw new IllegalStateException("jwt.token.secret-key must be at least 32 bytes");
    }
    this.key = Keys.hmacShaKeyFor(keyBytes);
  }

  public SecretKey getKey() {
    return key;
  }
}
