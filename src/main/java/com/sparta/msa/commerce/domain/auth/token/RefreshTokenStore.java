package com.sparta.msa.commerce.domain.auth.token;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenStore {

  private static final String PREFIX = "rt:";

  private final StringRedisTemplate redisTemplate;
  private final Duration ttl;

  public RefreshTokenStore(
      StringRedisTemplate redisTemplate,
      @Value("${jwt.token.refresh-exp-minute-time}") long refreshExpMinutes) {
    this.redisTemplate = redisTemplate;
    this.ttl = Duration.ofMinutes(refreshExpMinutes);
  }

  public void save(String token, Long userId, int tokenVersion) {
    redisTemplate.opsForValue().set(PREFIX + token, userId + ":" + tokenVersion, ttl);
  }

  // 원자적 조회+삭제(GETDEL) — 동시 재발급 시 한 요청만 성공(RTR 1회용 보장)
  public Optional<RefreshTokenInfo> getAndDelete(String token) {
    return parse(redisTemplate.opsForValue().getAndDelete(PREFIX + token));
  }

  public void delete(String token) {
    redisTemplate.delete(PREFIX + token);
  }

  private Optional<RefreshTokenInfo> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String[] parts = value.split(":", 2);
    if (parts.length < 2) {
      return Optional.empty();
    }
    try {
      return Optional.of(new RefreshTokenInfo(Long.parseLong(parts[0]), Integer.parseInt(parts[1])));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
