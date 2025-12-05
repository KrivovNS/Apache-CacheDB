package com.mipt.model;

import com.mipt.cache.*;
import com.mipt.controller.DataType;
import com.mipt.cache.CacheEntry;
import com.mipt.cache.CacheResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class CacheStorage {
  private static final int SEGMENTS_COUNT = 16;
  private static final int DEFAULT_SEGMENT = 0;

  private Long id;
  private String storageToken;
  private final long maxMemoryBytes;
  private final CacheType cacheType;

  private final Cache[] segments; // 16 сегментов
  private final Cache ttlCache;   // отдельный кэш для TTL данных
  private final Map<Object, Integer> keyToSegmentMap; // какой ключ в каком сегменте
  private final AtomicLong totalMemoryUsed;

  private final ScheduledExecutorService cleanupScheduler;

  public enum CacheType {
    LRU,
    SIMPLE
  }

  public CacheStorage(long maxMemoryBytes, CacheType cacheType) {
    this.maxMemoryBytes = maxMemoryBytes;
    this.cacheType = cacheType;
    this.totalMemoryUsed = new AtomicLong(0);
    this.keyToSegmentMap = new ConcurrentHashMap<>();

    // Создаем сегменты
    this.segments = new Cache[SEGMENTS_COUNT];
    long segmentMemory = maxMemoryBytes / SEGMENTS_COUNT;

    for (int i = 0; i < SEGMENTS_COUNT; i++) {
      segments[i] = createCache(segmentMemory);
    }

    // TTL кэш использует 20% от общей памяти
    this.ttlCache = createCache((long) (maxMemoryBytes * 0.2));

    // Запускаем очистку просроченных данных каждую минуту
    this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    this.cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredEntries,
        1, 1, TimeUnit.MINUTES);
  }

  private Cache createCache(long capacityBytes) {
    switch (cacheType) {
      case LRU:
        return new LRUCache((int) capacityBytes);
      case SIMPLE:
        return new SimpleCache((int) capacityBytes);
      default:
        throw new IllegalArgumentException("Unsupported cache type: " + cacheType);
    }
  }

  private int getSegmentForKey(String key) {
    if (key == null) return DEFAULT_SEGMENT;

    // Используем hashcode для распределения по сегментам
    return Math.abs(key.hashCode()) % SEGMENTS_COUNT;
  }

  private long calculateSize(Object data) {
    if (data == null) return 0;

    // Простая эвристика для оценки размера
    if (data instanceof String) {
      return ((String) data).getBytes().length;
    } else if (data instanceof byte[]) {
      return ((byte[]) data).length;
    } else if (data instanceof CacheEntry) {
      return ((CacheEntry) data).getSizeInBytes();
    } else {
      // Для других типов используем сериализацию в байты
      try {
        return data.toString().getBytes().length;
      } catch (Exception e) {
        return 1024; // default 1KB
      }
    }
  }

  public CacheResult put(String key, DataType dataType, Object data,
      String user, Long ttlMillis, Integer segment) {
    try {
      // Проверяем доступность памяти
      long estimatedSize = calculateSize(data);
      if (totalMemoryUsed.get() + estimatedSize > maxMemoryBytes) {
        return CacheResult.error("Memory limit exceeded. Current: " +
            totalMemoryUsed.get() + "/" + maxMemoryBytes + " bytes");
      }

      // Определяем сегмент
      int targetSegment = (segment != null && segment >= 0 && segment < SEGMENTS_COUNT)
          ? segment : getSegmentForKey(key);

      // Создаем CacheEntry
      CacheEntry entry = new CacheEntry(dataType, data, estimatedSize, user, ttlMillis);

      // Сохраняем в основном кэше
      Cache targetCache = segments[targetSegment];
      targetCache.put(key, entry, estimatedSize);
      keyToSegmentMap.put(key, targetSegment);
      totalMemoryUsed.addAndGet(estimatedSize);

      // Если есть TTL, сохраняем в TTL кэш
      if (ttlMillis != null && ttlMillis > 0) {
        ttlCache.put(key, entry, estimatedSize);
      }

      return CacheResult.success("Stored in segment " + targetSegment);
    } catch (Exception e) {
      return CacheResult.error("Error storing data: " + e.getMessage());
    }
  }

  public CacheResult get(String key) {
    try {
      // Проверяем в каком сегменте ключ
      Integer segment = keyToSegmentMap.get(key);
      if (segment == null) {
        return CacheResult.error("Key not found");
      }

      // Получаем из основного кэша
      CacheEntry entry = (CacheEntry) segments[segment].get(key);
      if (entry == null) {
        keyToSegmentMap.remove(key);
        return CacheResult.error("Key not found");
      }

      // Проверяем TTL
      if (entry.isExpired()) {
        delete(key);
        return CacheResult.error("Key expired");
      }

      // Обновляем статистику
      entry.incrementAccessCount();

      return CacheResult.success(entry);
    } catch (Exception e) {
      return CacheResult.error("Error retrieving data: " + e.getMessage());
    }
  }

  public CacheResult delete(String key) {
    try {
      Integer segment = keyToSegmentMap.remove(key);
      if (segment == null) {
        return CacheResult.error("Key not found");
      }

      // Удаляем из основного кэша
      CacheEntry entry = (CacheEntry) segments[segment].get(key);
      if (entry != null) {
        segments[segment].remove(key);
        totalMemoryUsed.addAndGet(-entry.getSizeInBytes());
      }

      // Удаляем из TTL кэша
      ttlCache.remove(key);

      return CacheResult.success("Deleted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error deleting data: " + e.getMessage());
    }
  }

  private void cleanupExpiredEntries() {
    try {
      // Очищаем TTL кэш и удаляем просроченные из всех сегментов
      for (Object key : ttlCache.getKeys()) {
        CacheEntry entry = (CacheEntry) ttlCache.get(key);
        if (entry != null && entry.isExpired()) {
          delete((String) key);
        }
      }
    } catch (Exception e) {
      System.err.println("Error during cleanup: " + e.getMessage());
    }
  }

  // Статистика
  public Map<String, Object> getStats() {
    Map<String, Object> stats = new HashMap<>();
    stats.put("totalMemoryUsed", totalMemoryUsed.get() + " bytes");
    stats.put("maxMemory", maxMemoryBytes + " bytes");
    stats.put("segmentsCount", SEGMENTS_COUNT);
    stats.put("cacheType", cacheType);

    Map<Integer, Integer> segmentSizes = new HashMap<>();
    for (int i = 0; i < SEGMENTS_COUNT; i++) {
      segmentSizes.put(i, segments[i].size());
    }
    stats.put("segmentSizes", segmentSizes);
    stats.put("ttlCacheSize", ttlCache.size());

    return stats;
  }

  public void shutdown() {
    cleanupScheduler.shutdown();
    try {
      cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // Геттеры и сеттеры
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getStorageToken() { return storageToken; }
  public void setStorageToken(String storageToken) { this.storageToken = storageToken; }
  public long getMaxMemoryBytes() { return maxMemoryBytes; }
  public CacheType getCacheType() { return cacheType; }
}