package com.mipt.service;

import com.mipt.cache.*;
import com.mipt.userstorage.dao.CacheStorageDAO;
import com.mipt.userstorage.model.CacheStorageEntity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStorageService {
  private final CacheStorageDAO cacheStorageDAO;
  private final Map<String, Cache> activeCaches;

  public CacheStorageService(CacheStorageDAO cacheStorageDAO) {
    this.cacheStorageDAO = cacheStorageDAO;
    this.activeCaches = new ConcurrentHashMap<>();
    initializeCaches();
  }

  private void initializeCaches() {
    for (CacheStorageEntity entity : cacheStorageDAO.findAll()) {
      createCacheInMemory(entity);
    }
  }

  public Cache createCacheInMemory(CacheStorageEntity entity) {
    Cache cache = new ConcurrentCache();
    activeCaches.put(entity.getStorageToken(), cache);
    return cache;
  }

  private Cache createCache() {
    return new LRUCache(2024);
  }

  /**
   * Создание нового кэш-хранилища
   */
  public boolean createCacheStorage(String storageName, String cacheType, Integer maxSize) {
    if (cacheExists(storageName)) {
      return false;
    }

    Cache cache;
    switch (cacheType.toUpperCase()) {
      case "SIMPLE":
        cache = new SimpleCache();
        break;
      case "CONCURRENT":
        cache = new ConcurrentCache();
        break;
      case "LRU":
        if (maxSize != null && maxSize > 0) {
          cache = new LRUCache(maxSize);
        } else {
          cache = new LRUCache(100);
        }
        break;
      default:
        cache = new ConcurrentCache();
    }

    activeCaches.put(storageName, cache);
    System.out.println("Created cache storage: " + storageName + " (type: " + cacheType + ")");
    return true;
  }

  public Cache getCache(String storageName) {
    return activeCaches.get(storageName);
  }

  public CacheStorageEntity getCacheEntity(String storageName) {
    return cacheStorageDAO.findByName(storageName);
  }

  /**
   * Проверка существования кэша
   */
  public boolean cacheExists(String storageName) {
    return activeCaches.containsKey(storageName);
  }

  // ========== ДОБАВЛЯЕМ МЕТОДЫ ДЛЯ FileDistributor ==========

  /**
   * Положить значение в кэш
   */
  public void put(String storageName, Object key, Object value) {
    Cache cache = getCache(storageName);
    if (cache != null) {
      cache.put(key, value);
    } else {
      System.err.println("Cache storage not found: " + storageName);
    }
  }

  /**
   * Получить значение из кэша
   */
  public Object get(String storageName, Object key) {
    Cache cache = getCache(storageName);
    return cache != null ? cache.get(key) : null;
  }

  /**
   * Удалить значение из кэша
   */
  public void remove(String storageName, Object key) {
    Cache cache = getCache(storageName);
    if (cache != null) {
      cache.remove(key);
    }
  }

  /**
   * Получить размер кэша
   */
  public int size(String storageName) {
    Cache cache = getCache(storageName);
    return cache != null ? cache.size() : 0;
  }

  /**
   * Проверить наличие ключа в кэше
   */
  public boolean containsKey(String storageName, Object key) {
    Cache cache = getCache(storageName);
    return cache != null && cache.containsKey(key);
  }

  /**
   * Очистить кэш
   */
  public void clear(String storageName) {
    Cache cache = getCache(storageName);
    if (cache != null) {
      cache.clear();
    }
  }
}