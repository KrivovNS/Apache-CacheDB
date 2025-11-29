package com.mipt.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class Session {
  private String sessionToken;
  private Instant createdAt;
  private CacheStorage storage;

  public Session(String sessionToken, CacheStorage storage) {
    this.sessionToken = sessionToken;
    this.storage = storage;
    this.createdAt = Instant.now();
  }

  public Session(String sessionToken, Instant createdAt, CacheStorage storage) {
    this.sessionToken = sessionToken;
    this.createdAt = createdAt;
    this.storage = storage;
  }

  // Геттеры
  public String getSessionToken() {
    return sessionToken;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public CacheStorage getStorage() {
    return storage;
  }

  /**
   * Проверяет валидность сессии
   */
  public boolean isValid(long ttlHours) {
    Instant expirationTime = createdAt.plus(ttlHours, ChronoUnit.HOURS);
    return Instant.now().isBefore(expirationTime);
  }

  /**
   * Возвращает количество часов до истечения сессии
   */
  public long getHoursUntilExpiration(long ttlHours) {
    Instant expirationTime = createdAt.plus(ttlHours, ChronoUnit.HOURS);
    return ChronoUnit.HOURS.between(Instant.now(), expirationTime);
  }

  /**
   * Обновляет время создания сессии
   */
  public void refresh() {
    this.createdAt = Instant.now();
  }
}