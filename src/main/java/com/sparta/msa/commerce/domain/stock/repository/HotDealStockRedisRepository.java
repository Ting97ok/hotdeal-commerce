package com.sparta.msa.commerce.domain.stock.repository;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class HotDealStockRedisRepository {

  private static final String KEY_PREFIX = "hotdeal:stock:";
  private static final RedisScript<Long> DEDUCT_SCRIPT = RedisScript.of("""
      local remaining = tonumber(redis.call('GET', KEYS[1]))
      if remaining == nil then return -1 end
      if remaining >= tonumber(ARGV[1]) then
        redis.call('DECRBY', KEYS[1], ARGV[1])
        return 1
      end
      return 0
      """, Long.class);

  private final StringRedisTemplate redisTemplate;

  public HotDealStockRedisRepository(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public void init(Long hotDealId, int quantity) {
    redisTemplate.opsForValue().set(key(hotDealId), String.valueOf(quantity));
  }

  public long deduct(Long hotDealId, int quantity) {
    return redisTemplate.execute(DEDUCT_SCRIPT, List.of(key(hotDealId)), String.valueOf(quantity));
  }

  public void restore(Long hotDealId, int quantity) {
    redisTemplate.opsForValue().increment(key(hotDealId), quantity);
  }

  private String key(Long hotDealId) {
    return KEY_PREFIX + hotDealId;
  }
}
