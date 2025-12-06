package com.mipt.service;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.CacheStorage;
import com.mipt.model.MaxMemoryPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CacheStorageService {

  private CacheStorage database;
  private final ScheduledExecutorService cleanupScheduler;

  private long maxMemory;
  private MaxMemoryPolicy maxMemoryPolicy;
  private boolean canPolicyBeChanged;

  public CacheStorageService() {
    try {
      Properties props = loadProperties();
      this.maxMemory = Long.parseLong(props.getProperty("cache.max.memory", "104857600"));
      this.maxMemoryPolicy = parsePolicy(props, "cache.max.memory.policy",
          MaxMemoryPolicy.ALLKEYSLRU);
      this.canPolicyBeChanged = true;

    } catch (IOException e) {
      this.maxMemory = 100 * 1024 * 1024;
      this.maxMemoryPolicy = MaxMemoryPolicy.ALLKEYSLRU;
      this.canPolicyBeChanged = true;
    }

    createCacheStorages();

    this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    this.cleanupScheduler.scheduleAtFixedRate(
        this::cleanupDatabase,
        1, 1, TimeUnit.MINUTES
    );
  }

  // Основные операции
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

  private Properties loadProperties() throws IOException {
    Properties props = new Properties();
    try (InputStream input = getClass().getClassLoader()
        .getResourceAsStream("application.properties")) {
      if (input != null) {
        props.load(input);
      }
    }
    return props;
  }

  private MaxMemoryPolicy parsePolicy(Properties props, String key, MaxMemoryPolicy defaultValue) {
    String value = props.getProperty(key);
    if (value != null) {
      try {
        return MaxMemoryPolicy.valueOf(value.toUpperCase());
      } catch (IllegalArgumentException e) {
        // Оставляем defaultValue
      }
    }
    return defaultValue;
  }

  private void createCacheStorages() {
    database = new CacheStorage(maxMemory, maxMemoryPolicy);
  }

  public long getmaxMemory() {
    return maxMemory;
  }
}