package com.mipt.service;

import static com.mipt.model.MaxMemoryPolicy.ALLKEYSLRU;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.CacheStorage;
import com.mipt.model.MaxMemoryPolicy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CacheStorageService {

  private static final long DEFAULT_MAX_MEMORY = 100 * 1024 * 1024; // 100MB

  private CacheStorage database;
  private final ScheduledExecutorService cleanupScheduler;

  private long maxMemory;
  private MaxMemoryPolicy maxMemoryPolicy;
  private boolean canPolicyBeChanged;

  public CacheStorageService() {
    this(DEFAULT_MAX_MEMORY, ALLKEYSLRU);
  }

  public CacheStorageService(long maxMemory, MaxMemoryPolicy maxMemoryPolicy) {
    this.maxMemory = maxMemory;
    this.maxMemoryPolicy = maxMemoryPolicy;

    createCacheStorages();

    // Запускаем периодическую очистку просроченных ключей
    this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    this.cleanupScheduler.scheduleAtFixedRate(this::cleanupDatabase,
        1, 1, TimeUnit.MINUTES);
    this.canPolicyBeChanged = true;
  }

  // Основные операции (работают с единственной БД)
  public CacheResult post(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    if (canPolicyBeChanged) {
      canPolicyBeChanged = false;
    }
    if (!database.containsKey(key)) {
      return database.set(key, value, dataType, user, ttlSeconds, sizeBytes);
    }
    return CacheResult.error("The key already exists");
  }

  public CacheResult put(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    if (database.containsKey(key)) {
      return database.set(key, value, dataType, user, ttlSeconds, sizeBytes);
    }
    return CacheResult.error("The key not exists");
  }

  public CacheResult get(String key) {
    return database.get(key);
  }

  public CacheResult delete(String key) {
    return database.delete(key);
  }

  // Вспомогательные методы
  private void cleanupDatabase() {
    int removed = database.cleanupExpired();
    if (removed > 0) {
      System.out.println("Cleanup removed " + removed + " expired keys");
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

  public CacheResult changePolicy(MaxMemoryPolicy maxMemoryPolicy, long totalMaxMemory) {
    if (canPolicyBeChanged) {
      this.maxMemoryPolicy = maxMemoryPolicy;
      this.maxMemory = totalMaxMemory;
      createCacheStorages();
      return CacheResult.success();
    }
    return CacheResult.error("Can't change configuration");
  }

  private void createCacheStorages() {
      database = new CacheStorage(maxMemory, maxMemoryPolicy);
  }

  public long getmaxMemory() {
    return maxMemory;
  }
}