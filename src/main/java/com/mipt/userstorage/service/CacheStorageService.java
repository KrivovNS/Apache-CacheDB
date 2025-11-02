package com.mipt.userstorage.service;

import com.mipt.cache.Cache;
import com.mipt.cache.SimpleCache;
import com.mipt.cache.ConcurrentCache;
import com.mipt.cache.LRUCache;
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

  /**
   * Инициализация кэшей из базы данных
   */
  private void initializeCaches() {
    for (CacheStorageEntity entity : cacheStorageDAO.findAll()) {
      createCacheInMemory(entity);
    }
  }

  /**
   * Создание кэша в памяти на основе entity из БД
   */
  public Cache createCacheInMemory(CacheStorageEntity entity) {
    Cache cache = createCacheByType(entity);
    activeCaches.put(entity.getStorageName(), cache);
    System.out.println("Created cache: " + entity.getStorageName() + " (" + entity.getCacheType() + ")");
    return cache;
  }

  /**
   * Создание кэша по типу из entity
   */
  private Cache createCacheByType(CacheStorageEntity entity) {
    switch (entity.getCacheType().toUpperCase()) {
      case "SIMPLE":
        return new SimpleCache();
      case "CONCURRENT":
        return new ConcurrentCache();
      case "LRU":
        int maxSize = entity.getMaxSize() != null ? entity.getMaxSize() : 100;
        return new LRUCache(maxSize);
      default:
        return new SimpleCache();
    }
  }

  /**
   * Получение кэша по имени
   */
  public Cache getCache(String storageName) {
    return activeCaches.get(storageName);
  }

  /**
   * Получение entity кэш-хранилища
   */
  public CacheStorageEntity getCacheEntity(String storageName) {
    return cacheStorageDAO.findByName(storageName);
  }

  /**
   * Проверка существования кэша
   */
  public boolean cacheExists(String storageName) {
    return activeCaches.containsKey(storageName);
  }
}