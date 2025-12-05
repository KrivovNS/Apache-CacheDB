package com.mipt.cache;

import com.mipt.controller.DataType;
import java.time.Instant;
import java.util.Objects;

public class CacheEntry {

  private final DataType dataType;
  private final Object data;
  private final long sizeInBytes;
  private final String createdByUser;
  private final Instant createdAt;
  private Instant expiresAt; // null если нет TTL
  private int accessCount;

  public CacheEntry(DataType dataType, Object data, long sizeInBytes,
      String createdByUser, Long ttlSeconds) {
    this.dataType = Objects.requireNonNull(dataType);
    this.data = Objects.requireNonNull(data);
    this.sizeInBytes = sizeInBytes;
    this.createdByUser = Objects.requireNonNull(createdByUser);
    this.createdAt = Instant.now();
    this.accessCount = 0;

    if (ttlSeconds != null && ttlSeconds > 0) {
      this.expiresAt = createdAt.plusSeconds(ttlSeconds);
    }
  }

  public boolean dataWithTtl() {
    return expiresAt != null;
  }

  public boolean isExpired() {
    if (expiresAt == null) {
      return false;
    }
    return Instant.now().isAfter(expiresAt);
  }

  public void incrementAccessCount() {
    accessCount++;
  }

  // Геттеры
  public DataType getDataType() {
    return dataType;
  }

  public Object getData() {
    return data;
  }

  public long getSizeInBytes() {
    return sizeInBytes;
  }

  public String getCreatedByUser() {
    return createdByUser;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public int getAccessCount() {
    return accessCount;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  @Override
  public String toString() {
    return "CacheEntry{" +
        "dataType=" + dataType +
        ", size=" + sizeInBytes + " bytes" +
        ", user='" + createdByUser + '\'' +
        ", accessCount=" + accessCount +
        ", expiresAt=" + (expiresAt != null ? expiresAt : "never") +
        '}';
  }
}