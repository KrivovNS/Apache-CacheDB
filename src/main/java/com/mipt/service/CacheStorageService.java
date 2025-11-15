package com.mipt.service;

import com.mipt.cache.Cache;
import com.mipt.cache.LRUCache;
import com.mipt.cache.CacheResult;
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
    Cache cache = new LRUCache(2024);
    activeCaches.put(entity.getStorageToken(), cache);
    return cache;
  }

  public Cache getCache(String storageToken) {
    return activeCaches.get(storageToken);
  }

  public CacheStorageEntity getCacheEntity(String storageToken) {
    return cacheStorageDAO.findByName(storageToken);
  }

  public boolean cacheExists(String storageToken) {
    return activeCaches.containsKey(storageToken);
  }

  public CacheResult read(String storageToken, String key, String type) {
    try {
      Cache cache = activeCaches.get(storageToken);
      if (cache == null) {
        return CacheResult.error("Cache with token " + storageToken + " not found");
      }

      Object value = cache.get(key);
      if (value == null) {
        return CacheResult.error("Key " + key + " not found");
      }

      return CacheResult.success(value);
    } catch (Exception e) {
      return CacheResult.error("Error reading cache: " + e.getMessage());
    }
  }

  public CacheResult insert(String storageToken, String key, String type, String value) {
    try {
      Cache cache = activeCaches.get(storageToken);
      if (cache == null) {
        return CacheResult.error("Cache with token " + storageToken + " not found");
      }

      if (cache.containsKey(key)) {
        return CacheResult.error("Key " + key + " already exists");
      }

      Object typedValue = convertValue(type, value);
      if (typedValue == null) {
        return CacheResult.error("Unsupported data type: " + type);
      }

      cache.put(key, typedValue);
      return CacheResult.success("Inserted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error inserting cache: " + e.getMessage());
    }
  }

  public CacheResult put(String storageToken, String key, String type, String value) {
    try {
      Cache cache = activeCaches.get(storageToken);
      if (cache == null) {
        return CacheResult.error("Cache with token " + storageToken + " not found");
      }

      Object typedValue = convertValue(type, value);
      if (typedValue == null) {
        return CacheResult.error("Unsupported data type: " + type);
      }

      cache.put(key, typedValue);
      return CacheResult.success("Put successfully");
    } catch (Exception e) {
      return CacheResult.error("Error putting cache: " + e.getMessage());
    }
  }

  public CacheResult delete(String storageToken, String key, String type) {
    try {
      Cache cache = activeCaches.get(storageToken);
      if (cache == null) {
        return CacheResult.error("Cache with token " + storageToken + " not found");
      }

      if (!cache.containsKey(key)) {
        return CacheResult.error("Key " + key + " not found");
      }

      cache.remove(key);
      return CacheResult.success("Deleted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error deleting cache: " + e.getMessage());
    }
  }

  private Object convertValue(String type, String value) {
    try {
      switch (type.toLowerCase()) {
        case "string":
          return value;
        case "integer":
        case "int":
          return Integer.parseInt(value);
        case "long":
          return Long.parseLong(value);
        case "double":
          return Double.parseDouble(value);
        case "float":
          return Float.parseFloat(value);
        case "boolean":
        case "bool":
          return Boolean.parseBoolean(value);
        default:
          return null;
      }
    } catch (NumberFormatException e) {
      return null;
    }
  }
}