package com.mipt.service;

import com.mipt.cache.Cache;
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
    Cache cache = createCache();
    activeCaches.put(entity.getStorageToken(), cache);
    return cache;
  }

  /**
   * Создание кэша по типу из entity
   */
  private Cache createCache() {
    return new LRUCache(2024);
  }

  /**
   * Получение кэша по имени
   */
  public Cache getCache(String storageToken) {
    return activeCaches.get(storageToken);
  }

  /**
   * Получение entity кэш-хранилища
   */
  public CacheStorageEntity getCacheEntity(String storageToken) {
    return cacheStorageDAO.findByName(storageToken);
  }

  /**
   * Проверка существования кэша
   */
  public boolean cacheExists(String storageToken) {
    return !activeCaches.containsKey(storageToken);
  }
}