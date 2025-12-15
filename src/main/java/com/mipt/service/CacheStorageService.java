package com.mipt.service;

import com.mipt.cache.CacheResult;
import com.mipt.database.dao.CacheEntryDAO;
import com.mipt.model.DataType;
import com.mipt.model.CacheStorage;
import com.mipt.model.MaxMemoryPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
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
  private boolean persistence;
  private final CacheEntryDAO cacheEntryDAO;

  public CacheStorageService() {
    try {
      Properties props = loadProperties();
      this.maxMemory = Long.parseLong(props.getProperty("cache.max.memory", "104857600"));
      this.maxMemoryPolicy = parsePolicy(props, "cache.max.memory.policy",
          MaxMemoryPolicy.ALLKEYSLRU);
      this.canPolicyBeChanged = true;
      this.persistence = Boolean.parseBoolean(props.getProperty("cache.persistence", "false"));

    } catch (IOException e) {
      this.maxMemory = 100 * 1024 * 1024;
      this.maxMemoryPolicy = MaxMemoryPolicy.ALLKEYSLRU;
      this.canPolicyBeChanged = true;
      this.persistence = false;
    }

    cacheEntryDAO = new CacheEntryDAO();

    createCacheStorages();

    // Загружаем данные из БД если persistence включен
    if (persistence) {
      loadFromDatabase();
    }

    this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    this.cleanupScheduler.scheduleAtFixedRate(
        this::cleanupDatabase,
        1, 1, TimeUnit.MINUTES
    );
  }

  /**
   * Загружает данные из базы данных в кэш
   */
  public void loadFromDatabase() {
    try {
      // Создаем таблицы если их нет
      cacheEntryDAO.createAllTables();
      // Загружаем данные из БД в кэш
      cacheEntryDAO.loadEntriesIntoCacheStorage(this);

      System.out.println("Данные успешно загружены из базы данных");

    } catch (SQLException e) {
      System.err.println("Ошибка при загрузке данных из БД: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // Основные операции
  public CacheResult post(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    if (canPolicyBeChanged) {
      canPolicyBeChanged = false;
    }
    if (!database.containsKey(key)) {
      return database.set(key, value, dataType, user, ttlSeconds);
    }
    return CacheResult.error("The key already exists");
  }

  public CacheResult put(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {
    if (database.containsKey(key)) {
      return database.set(key, value, dataType, user, ttlSeconds);
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

  public CacheResult changePolicy(MaxMemoryPolicy maxMemoryPolicy, long totalMaxMemory, boolean persistence) {
    if (canPolicyBeChanged) {
      this.maxMemoryPolicy = maxMemoryPolicy;
      this.maxMemory = totalMaxMemory;
      this.persistence = persistence;
      createCacheStorages();
      if (persistence) {
        loadFromDatabase();
      }
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
        return MaxMemoryPolicy.ALLKEYSLRU;
      }
    }
    return defaultValue;
  }

  private void createCacheStorages() {
    database = new CacheStorage(maxMemory, maxMemoryPolicy, persistence, cacheEntryDAO);
  }
}