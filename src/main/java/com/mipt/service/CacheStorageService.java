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
  private final Map<String, Map<String, Cache>> storageCaches; // storageToken -> (type -> cache)

  public CacheStorageService(CacheStorageDAO cacheStorageDAO) {
    this.cacheStorageDAO = cacheStorageDAO;
    this.storageCaches = new ConcurrentHashMap<>();
    initializeCaches();
  }

  private void initializeCaches() {
    for (CacheStorageEntity entity : cacheStorageDAO.findAll()) {
      createStorageCaches(entity.getStorageToken());
    }
  }

  private void createStorageCaches(String storageToken) {
    Map<String, Cache> typeCaches = new ConcurrentHashMap<>();
    typeCaches.put("json", new LRUCache(1000));
    typeCaches.put("byte[]", new LRUCache(1000));
    typeCaches.put("string", new LRUCache(1000));
    storageCaches.put(storageToken, typeCaches);
  }

  public CacheResult readData(String storageToken, String dataType, String key) {
    try {
      Map<String, Cache> typeCaches = storageCaches.get(storageToken);
      if (typeCaches == null) {
        return CacheResult.error("Storage " + storageToken + " not found");
      }

      Cache cache = typeCaches.get(dataType.toLowerCase());
      if (cache == null) {
        return CacheResult.error("Data type " + dataType + " not supported");
      }

      Object value = cache.get(key);
      if (value == null) {
        return CacheResult.error("Key " + key + " not found");
      }

      return CacheResult.success(value);
    } catch (Exception e) {
      return CacheResult.error("Error reading data: " + e.getMessage());
    }
  }

  public CacheResult insertData(String storageToken, String dataType, String key, String value) {
    try {
      Map<String, Cache> typeCaches = storageCaches.get(storageToken);
      if (typeCaches == null) {
        return CacheResult.error("Storage " + storageToken + " not found");
      }

      Cache cache = typeCaches.get(dataType.toLowerCase());
      if (cache == null) {
        return CacheResult.error("Data type " + dataType + " not supported");
      }

      if (cache.containsKey(key)) {
        return CacheResult.error("Key " + key + " already exists");
      }

      cache.put(key, value);
      return CacheResult.success("Data inserted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error inserting data: " + e.getMessage());
    }
  }

  public CacheResult updateData(String storageToken, String dataType, String key, String value) {
    try {
      Map<String, Cache> typeCaches = storageCaches.get(storageToken);
      if (typeCaches == null) {
        return CacheResult.error("Storage " + storageToken + " not found");
      }

      Cache cache = typeCaches.get(dataType.toLowerCase());
      if (cache == null) {
        return CacheResult.error("Data type " + dataType + " not supported");
      }

      cache.put(key, value);
      return CacheResult.success("Data updated successfully");
    } catch (Exception e) {
      return CacheResult.error("Error updating data: " + e.getMessage());
    }
  }

  public CacheResult deleteData(String storageToken, String dataType, String key) {
    try {
      Map<String, Cache> typeCaches = storageCaches.get(storageToken);
      if (typeCaches == null) {
        return CacheResult.error("Storage " + storageToken + " not found");
      }

      Cache cache = typeCaches.get(dataType.toLowerCase());
      if (cache == null) {
        return CacheResult.error("Data type " + dataType + " not supported");
      }

      if (!cache.containsKey(key)) {
        return CacheResult.error("Key " + key + " not found");
      }

      cache.remove(key);
      return CacheResult.success("Data deleted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error deleting data: " + e.getMessage());
    }
  }

  public boolean storageExists(String storageToken) {
    return storageCaches.containsKey(storageToken);
  }

  public Cache getCache(String storageToken, String dataType) {
    Map<String, Cache> typeCaches = storageCaches.get(storageToken);
    return typeCaches != null ? typeCaches.get(dataType.toLowerCase()) : null;
  }
}