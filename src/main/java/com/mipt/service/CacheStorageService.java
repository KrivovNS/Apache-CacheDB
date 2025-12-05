package com.mipt.service;

import static com.mipt.model.MaxMemoryPolicy.ALLKEYSLRU;

import com.mipt.cache.CacheResult;
import com.mipt.controller.DataType;
import com.mipt.model.CacheStorage;
import com.mipt.model.MaxMemoryPolicy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CacheStorageService {

  private static final int DEFAULT_DB_COUNT = 16;
  private static final long DEFAULT_MAX_MEMORY = 100 * 1024 * 1024; // 100MB

  private final CacheStorage[] databases;
  private final ScheduledExecutorService cleanupScheduler;

  private int currentDbIndex = 0;
  private long maxMemoryPerDb;
  private MaxMemoryPolicy maxMemoryPolicy;

  public CacheStorageService() {
    this(DEFAULT_MAX_MEMORY, ALLKEYSLRU);
  }

  public CacheStorageService(long totalMaxMemory, MaxMemoryPolicy maxMemoryPolicy) {
    this.maxMemoryPerDb = totalMaxMemory / DEFAULT_DB_COUNT;
    this.maxMemoryPolicy = maxMemoryPolicy;

    // Инициализируем 16 БД
    this.databases = new CacheStorage[DEFAULT_DB_COUNT];
    for (int i = 0; i < DEFAULT_DB_COUNT; i++) {
      databases[i] = new CacheStorage(maxMemoryPerDb, maxMemoryPolicy);
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

  // Основные операции (работают с текущей БД)
  public CacheResult post(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    if (!databases[currentDbIndex].containsKey(key)) {
      return databases[currentDbIndex].set(key, value, dataType, user, ttlSeconds, sizeBytes);
    }
    return CacheResult.error("The key already exists");
  }

  public CacheResult put(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    if (databases[currentDbIndex].containsKey(key)) {
      return databases[currentDbIndex].set(key, value, dataType, user, ttlSeconds, sizeBytes);
    }
    return CacheResult.error("The key not exists");
  }

  public CacheResult get(String key) {
    return databases[currentDbIndex].get(key);
  }

  public CacheResult delete(String key) {
    return databases[currentDbIndex].delete(key);
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
  public int getCurrentDbIndex() {
    return currentDbIndex;
  }

  public long getMaxMemoryPerDb() {
    return maxMemoryPerDb;
  }
}