package com.mipt.cache;

import java.util.Set;

public interface Cache {
  void put(Object key, Object value, long size);
  Object get(Object key);
  void remove(Object key);
  void clear();
  int size();
  long totalSizeInBytes();
  boolean containsKey(Object key);
  Set<Object> getKeys();

  // Новые методы для TTL и управления памятью
  default void putWithTTL(Object key, Object value, long size, Long ttlMillis) {
    put(key, value, size);
  }

  default boolean isKeyExpired(Object key) {
    return false;
  }

  default void cleanupExpired() {}
}
