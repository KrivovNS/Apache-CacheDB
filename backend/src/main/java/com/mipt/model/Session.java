package com.mipt.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class Session {
  private User creator;
  private PermissionType permissionType;
  private Instant createdAt;

  public Session(User creator, PermissionType permissionType) {
    this.creator = creator;
    this.permissionType = permissionType;
    this.createdAt = Instant.now();
  }

  // Геттеры
  public Instant getCreatedAt() {
    return createdAt;
  }

  public PermissionType getPermissionType() {return permissionType; }

  public User getCreator() {return creator; }


  /**
   * Проверяет валидность сессии
   */
  public boolean isValid(long ttlMinutes) {
    Instant expirationTime = createdAt.plus(ttlMinutes, ChronoUnit.MINUTES);
    return Instant.now().isBefore(expirationTime);
  }

  public void refresh() {
    createdAt = Instant.now();
  }
}