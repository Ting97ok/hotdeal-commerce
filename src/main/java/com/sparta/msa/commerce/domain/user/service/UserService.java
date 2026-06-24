package com.sparta.msa.commerce.domain.user.service;

import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.exception.UserExceptionCode;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import com.sparta.msa.commerce.global.exception.DomainException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional
  public User register(String email, String rawPassword, String name) {
    if (userRepository.existsByEmail(email)) {
      throw new DomainException(UserExceptionCode.DUPLICATE_EMAIL);
    }
    String encodedPassword = passwordEncoder.encode(rawPassword);
    User user = User.create(email, encodedPassword, name, UserRole.USER);
    return userRepository.save(user);
  }

  public Optional<User> findByEmail(String email) {
    return userRepository.findByEmail(email);
  }

  public User getUser(Long id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new DomainException(UserExceptionCode.USER_NOT_FOUND));
  }

  @Transactional
  public void incrementTokenVersion(Long id) {
    User user = getUser(id);
    user.incrementTokenVersion();
  }
}
