package com.sparta.msa.commerce.domain.user.entity;

import static lombok.AccessLevel.PRIVATE;

import com.sparta.msa.commerce.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Getter
@Builder(access = PRIVATE)
@NoArgsConstructor
@AllArgsConstructor(access = PRIVATE)
@FieldDefaults(level = PRIVATE)
@DynamicInsert
@DynamicUpdate
@Table(name = "users")
public class User extends BaseEntity {

  @Column(nullable = false, unique = true, length = 255)
  String email;

  @Column(nullable = false, length = 255)
  String password;

  @Column(nullable = false, length = 50)
  String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  UserRole role;

  @Column(name = "token_version", nullable = false)
  @Builder.Default
  int tokenVersion = 0;

  public static User create(String email, String encodedPassword, String name, UserRole role) {
    return User.builder()
        .email(email)
        .password(encodedPassword)
        .name(name)
        .role(role)
        .build();
  }

  public void incrementTokenVersion() {
    this.tokenVersion++;
  }

  public boolean matchesTokenVersion(int tokenVersion) {
    return this.tokenVersion == tokenVersion;
  }
}
