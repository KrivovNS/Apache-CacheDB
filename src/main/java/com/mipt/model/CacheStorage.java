package com.mipt.model;

import com.mipt.cache.*;
import com.mipt.controller.DataType;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class CacheStorage {
  private final Cache mainCache;      // Основное хранилище (LRU или Simple)
  private final Cache ttlCache;       // Хранилище для ключей с TTL
  private final AtomicLong memoryUsed;
  private final long maxMemoryBytes;

  // Конструктор
  public CacheStorage(long maxMemoryBytes, CacheType cacheType) {
    this.maxMemoryBytes = maxMemoryBytes;
    this.memoryUsed = new AtomicLong(0);

    // Разделяем память: 80% на основной кэш, 20% на TTL
    long mainCacheMemory = (long) (maxMemoryBytes * 0.8);
    long ttlCacheMemory = (long) (maxMemoryBytes * 0.2);

    this.mainCache = createCache(mainCacheMemory, cacheType);
    this.ttlCache = createCache(ttlCacheMemory, cacheType);
  }

  private Cache createCache(long capacityBytes, CacheType type) {
    return (type == CacheType.LRU)
        ? new LRUCache((int) capacityBytes)
        : new SimpleCache((int) capacityBytes);
  }

  public enum CacheType {
    LRU,
    SIMPLE
  }

  // Основные операции
  public CacheResult set(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    try {
      // Проверяем доступность памяти
      if (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
        return CacheResult.error("Memory limit exceeded");
      }

      // Создаем CacheEntry
      CacheEntry entry = new CacheEntry(
          dataType, value, sizeBytes, user,
          (ttlSeconds != null && ttlSeconds > 0) ? ttlSeconds * 1000L : null
      );

      // Сохраняем в основной кэш
      mainCache.put(key, entry, sizeBytes);
      memoryUsed.addAndGet(sizeBytes);

      // Если есть TTL, сохраняем в TTL кэш
      if (ttlSeconds != null && ttlSeconds > 0) {
        ttlCache.put(key, entry, sizeBytes);
      }

      return CacheResult.success("OK");
    } catch (Exception e) {
      return CacheResult.error("SET error: " + e.getMessage());
    }
  }

  public CacheResult get(String key) {
    try {
      // Проверяем наличие в TTL кэше (быстрая проверка)
      CacheEntry ttlEntry = (CacheEntry) ttlCache.get(key);
      if (ttlEntry != null && ttlEntry.isExpired()) {
        // Удаляем просроченный ключ
        delete(key);
        return CacheResult.error("Key expired");
      }

      // Получаем из основного кэша
      CacheEntry entry = (CacheEntry) mainCache.get(key);
      if (entry == null) {
        return CacheResult.error("Key not found");
      }

      // Двойная проверка TTL
      if (entry.isExpired()) {
        delete(key);
        return CacheResult.error("Key expired");
      }

      // Обновляем статистику
      entry.incrementAccessCount();

      return CacheResult.success(entry.getData());
    } catch (Exception e) {
      return CacheResult.error("GET error: " + e.getMessage());
    }
  }

  public CacheResult delete(String key) {
    try {
      CacheEntry entry = (CacheEntry) mainCache.get(key);
      if (entry != null) {
        mainCache.remove(key);
        ttlCache.remove(key);
        memoryUsed.addAndGet(-entry.getSizeInBytes());
        return CacheResult.success("Deleted");
      }
      return CacheResult.error("Key not found");
    } catch (Exception e) {
      return CacheResult.error("DEL error: " + e.getMessage());
    }
  }

  public CacheResult expire(String key, long ttlSeconds) {
    try {
      CacheEntry entry = (CacheEntry) mainCache.get(key);
      if (entry == null) {
        return CacheResult.error("Key not found");
      }

      // Обновляем TTL
      entry.setExpiresAt(
          Instant.now().plusSeconds(ttlSeconds)
      );

      // Обновляем в TTL кэше
      if (ttlSeconds > 0) {
        ttlCache.put(key, entry, entry.getSizeInBytes());
      } else {
        ttlCache.remove(key);
      }

      return CacheResult.success("TTL set");
    } catch (Exception e) {
      return CacheResult.error("EXPIRE error: " + e.getMessage());
    }
  }

  // Методы для очистки просроченных ключей
  public int cleanupExpired() {
    int removed = 0;
    // Проходим по TTL кэшу
    for (Object key : ttlCache.getKeys()) {
      CacheEntry entry = (CacheEntry) ttlCache.get(key);
      if (entry != null && entry.isExpired()) {
        delete((String) key);
        removed++;
      }
    }
    return removed;
  }

  // Статистика
  public StorageStats getStats() {
    return new StorageStats(
        mainCache.size(),
        ttlCache.size(),
        memoryUsed.get(),
        maxMemoryBytes
    );
  }

  // Геттеры
  public long getMemoryUsed() { return memoryUsed.get(); }
  public long getMaxMemory() { return maxMemoryBytes; }
  public Cache getMainCache() { return mainCache; }
  public Cache getTtlCache() { return ttlCache; }

  // DTO для статистики
  public static class StorageStats {
    public final int totalKeys;
    public final int ttlKeys;
    public final long memoryUsed;
    public final long maxMemory;

    public StorageStats(int totalKeys, int ttlKeys, long memoryUsed, long maxMemory) {
      this.totalKeys = totalKeys;
      this.ttlKeys = ttlKeys;
      this.memoryUsed = memoryUsed;
      this.maxMemory = maxMemory;
    }
  }
}