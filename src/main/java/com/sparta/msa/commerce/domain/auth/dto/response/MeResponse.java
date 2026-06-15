package com.sparta.msa.commerce.domain.auth.dto.response;

import com.sparta.msa.commerce.domain.user.entity.User;

public record MeResponse(Long userId, String email, String name, String role) {

  public static MeResponse from(User user) {
    return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name());
  }
}
