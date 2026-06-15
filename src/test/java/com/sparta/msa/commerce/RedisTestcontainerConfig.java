package com.sparta.msa.commerce;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainerConfig {

  @Bean
  @ServiceConnection(name = "redis")
  public GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);
  }
}
