package com.sparta.msa.commerce.domain.auth.token;

public record RefreshTokenInfo(Long userId, int tokenVersion) {
}
