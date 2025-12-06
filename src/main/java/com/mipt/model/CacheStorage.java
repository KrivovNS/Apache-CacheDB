package com.mipt.model;

import com.mipt.cache.*;
import java.util.concurrent.atomic.AtomicLong;

public class CacheStorage {

  private final Cache mainCache;      // Основное хранилище (LRU или Simple)
  private final Cache ttlCache;       // Хранилище для ключей с TTL
  private final AtomicLong memoryUsed;
  private final long maxMemoryBytes;
  private final MaxMemoryPolicy maxMemoryPolicy;

  // Конструктор
  public CacheStorage(long maxMemoryBytes, MaxMemoryPolicy maxMemoryPolicy) {
    this.maxMemoryBytes = maxMemoryBytes;
    this.memoryUsed = new AtomicLong(0);
    this.maxMemoryPolicy = maxMemoryPolicy;
    CacheType cacheType;
    switch (maxMemoryPolicy) {
      case NOEVICTION -> cacheType = CacheType.SIMPLE;
      case ALLKEYSLRU, VOLATILELRU -> cacheType = CacheType.LRU;
      default -> cacheType = CacheType.LRU;
    }
    this.mainCache = createCache(cacheType);
    this.ttlCache = createCache(cacheType);
  }

  private Cache createCache(CacheType type) {
    return (type == CacheType.LRU)
        ? new LRUCache()
        : new SimpleCache();
  }

  // Основные операции
  public CacheResult set(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    if (sizeBytes > maxMemoryBytes) {
      return CacheResult.error("Data exceeding the memory limit");
    }

    try {
      // Проверяем доступность памяти
      if (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
        switch (maxMemoryPolicy) {
          case NOEVICTION -> {
            return CacheResult.error("Memory overflow");
          }
          case ALLKEYSLRU -> {
            while (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
              Object keyToDelete = mainCache.freeMemory();
              CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
              memoryUsed.addAndGet(-(cacheEntry.getSizeInBytes()));
              if (cacheEntry.dataWithTtl()) {
                ttlCache.remove(keyToDelete);
              }
              mainCache.remove(keyToDelete);
            }
          }
          case VOLATILELRU -> {
            while (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
              Object keyToDelete;
              if (ttlCache.size() > 0) {
                keyToDelete = ttlCache.freeMemory();
              } else {
                keyToDelete = mainCache.freeMemory();
              }
              CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
              memoryUsed.addAndGet(-(cacheEntry.getSizeInBytes()));
              if (cacheEntry.dataWithTtl()) {
                ttlCache.remove(keyToDelete);
              }
              ;
              mainCache.remove(keyToDelete);
            }
          }
        }
      }

      // Создаем CacheEntry
      CacheEntry entry = new CacheEntry(
          dataType, value, sizeBytes, user, ttlSeconds
      );

      // Сохраняем в основной кэш
      mainCache.put(key, entry);
      memoryUsed.addAndGet(sizeBytes);

      // Если есть TTL, сохраняем в TTL кэш
      if (ttlSeconds != null && ttlSeconds > 0) {
        ttlCache.put(key, entry);
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

  public boolean containsKey(String key) {
    return mainCache.containsKey(key);
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

  // Геттеры
  public long getMemoryUsed() {
    return memoryUsed.get();
  }

  public long getMaxMemory() {
    return maxMemoryBytes;
  }
}