package com.mipt.service;

import com.mipt.cache.CacheResult;
import com.mipt.controller.DataType;
import com.mipt.model.CacheStorage;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CacheStorageService {
  private static final int DEFAULT_DB_COUNT = 16;
  private static final long DEFAULT_MAX_MEMORY = 100 * 1024 * 1024; // 100MB

  private final CacheStorage[] databases;
  private final Map<String, Integer> sessionToDbMap; // сессия → номер БД
  private final ScheduledExecutorService cleanupScheduler;

  private int currentDbIndex = 0;
  private long maxMemoryPerDb;
  private CacheStorage.CacheType cacheType;

  public CacheStorageService() {
    this(DEFAULT_MAX_MEMORY, CacheStorage.CacheType.LRU);
  }

  public CacheStorageService(long totalMaxMemory, CacheStorage.CacheType cacheType) {
    this.maxMemoryPerDb = totalMaxMemory / DEFAULT_DB_COUNT;
    this.cacheType = cacheType;
    this.sessionToDbMap = new ConcurrentHashMap<>();

    // Инициализируем 16 БД
    this.databases = new CacheStorage[DEFAULT_DB_COUNT];
    for (int i = 0; i < DEFAULT_DB_COUNT; i++) {
      databases[i] = new CacheStorage(maxMemoryPerDb, cacheType);
    }

    // Запускаем периодическую очистку просроченных ключей
    this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    this.cleanupScheduler.scheduleAtFixedRate(this::cleanupAllDatabases,
        1, 1, TimeUnit.MINUTES);
  }

  // Выбор БД
  public CacheResult selectDb(int dbIndex) {
    if (dbIndex < 0 || dbIndex >= databases.length) {
      return CacheResult.error("DB index out of range (0-15)");
    }
    currentDbIndex = dbIndex;
    return CacheResult.success("Switched to DB " + dbIndex);
  }

  public CacheResult selectDbForSession(String sessionToken, int dbIndex) {
    if (dbIndex < 0 || dbIndex >= databases.length) {
      return CacheResult.error("DB index out of range (0-15)");
    }
    sessionToDbMap.put(sessionToken, dbIndex);
    return CacheResult.success("Session " + sessionToken + " using DB " + dbIndex);
  }

  // Основные операции (работают с текущей БД)
  public CacheResult set(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    return databases[currentDbIndex].set(key, value, dataType, user, ttlSeconds, sizeBytes);
  }

  public CacheResult setForSession(String sessionToken, String key, Object value,
      DataType dataType, String user, Long ttlSeconds, long sizeBytes) {
    Integer dbIndex = sessionToDbMap.get(sessionToken);
    if (dbIndex == null) {
      dbIndex = 0; // Дефолтная БД для сессии
      sessionToDbMap.put(sessionToken, dbIndex);
    }
    return databases[dbIndex].set(key, value, dataType, user, ttlSeconds, sizeBytes);
  }

  public CacheResult get(String key) {
    return databases[currentDbIndex].get(key);
  }

  public CacheResult getForSession(String sessionToken, String key) {
    Integer dbIndex = sessionToDbMap.get(sessionToken);
    if (dbIndex == null) {
      return CacheResult.error("Session not found or no DB selected");
    }
    return databases[dbIndex].get(key);
  }

  public CacheResult delete(String key) {
    return databases[currentDbIndex].delete(key);
  }

  public CacheResult deleteForSession(String sessionToken, String key) {
    Integer dbIndex = sessionToDbMap.get(sessionToken);
    if (dbIndex == null) {
      return CacheResult.error("Session not found or no DB selected");
    }
    return databases[dbIndex].delete(key);
  }

  // Управление TTL
  public CacheResult expire(String key, long ttlSeconds) {
    return databases[currentDbIndex].expire(key, ttlSeconds);
  }

  public CacheResult ttl(String key) {
    // Реализовать получение оставшегося времени TTL
    return CacheResult.error("Not implemented yet");
  }

  // Очистка БД
  public CacheResult flushDb() {
    databases[currentDbIndex] = new CacheStorage(maxMemoryPerDb, cacheType);
    return CacheResult.success("DB flushed");
  }

  public CacheResult flushAll() {
    for (int i = 0; i < databases.length; i++) {
      databases[i] = new CacheStorage(maxMemoryPerDb, cacheType);
    }
    return CacheResult.success("All DBs flushed");
  }

  // Мониторинг и статистика
  public CacheResult info() {
    StringBuilder info = new StringBuilder();
    info.append("# Memory\n");
    info.append("maxmemory_per_db:").append(maxMemoryPerDb).append("\n");
    info.append("cache_type:").append(cacheType).append("\n");
    info.append("db_count:").append(databases.length).append("\n\n");

    for (int i = 0; i < databases.length; i++) {
      CacheStorage.StorageStats stats = databases[i].getStats();
      info.append("# DB").append(i).append("\n");
      info.append("keys:").append(stats.totalKeys).append("\n");
      info.append("ttl_keys:").append(stats.ttlKeys).append("\n");
      info.append("memory_used:").append(stats.memoryUsed).append("\n");
      info.append("memory_percentage:").append(String.format("%.1f%%",
          (stats.memoryUsed * 100.0 / stats.maxMemory))).append("\n\n");
    }

    return CacheResult.success(info.toString());
  }

  public CacheStorage.StorageStats getCurrentDbStats() {
    return databases[currentDbIndex].getStats();
  }

  public CacheStorage.StorageStats getDbStats(int dbIndex) {
    if (dbIndex < 0 || dbIndex >= databases.length) {
      return null;
    }
    return databases[dbIndex].getStats();
  }

  // Вспомогательные методы
  private void cleanupAllDatabases() {
    int totalRemoved = 0;
    for (CacheStorage db : databases) {
      totalRemoved += db.cleanupExpired();
    }
    if (totalRemoved > 0) {
      System.out.println("Cleanup removed " + totalRemoved + " expired keys");
    }
  }

  public void shutdown() {
    cleanupScheduler.shutdown();
    try {
      cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // Геттеры
  public int getCurrentDbIndex() { return currentDbIndex; }
  public int getDbCount() { return databases.length; }
  public long getMaxMemoryPerDb() { return maxMemoryPerDb; }
  public CacheStorage.CacheType getCacheType() { return cacheType; }

  public Optional<CacheStorage> getDatabase(int index) {
    if (index < 0 || index >= databases.length) {
      return Optional.empty();
    }
    return Optional.of(databases[index]);
  }
}