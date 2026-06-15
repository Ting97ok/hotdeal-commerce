package com.sparta.msa.commerce.domain.auth.dto.response;

public record TokenResponse(String accessToken, String refreshToken) {
}
