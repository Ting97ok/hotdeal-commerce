package com.sparta.msa.commerce.infrastructure.paymentgateway.toss;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toss")
public record TossProperties(
    String baseUrl,
    String secretKey,
    Duration connectTimeout,
    Duration readTimeout
) {}
