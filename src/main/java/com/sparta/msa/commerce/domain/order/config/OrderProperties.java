package com.sparta.msa.commerce.domain.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order")
public record OrderProperties(Duration paymentTimeout) {
}
