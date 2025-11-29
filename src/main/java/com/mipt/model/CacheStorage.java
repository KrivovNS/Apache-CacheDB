package com.mipt.model;

import com.mipt.cache.Cache;
import com.mipt.cache.CacheResult;
import com.mipt.cache.LRUCache;
import com.mipt.controller.DataType;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStorage {

  private Long id;
  private String storageToken;

  private final Map<DataType, LRUCache> typeCaches;
  private final int defaultCapacity;

  public CacheStorage(int defaultCapacity) {
    this.defaultCapacity = defaultCapacity;
    this.typeCaches = new ConcurrentHashMap<>();
  }

  public CacheStorage() {
    this(1000);
  }

  public CacheStorage(Long id, String storageToken, int defaultCapacity) {
    this(defaultCapacity);
    this.id = id;
    this.storageToken = storageToken;
  }

  private LRUCache getOrCreateTypeCache(DataType type) {
    return typeCaches.computeIfAbsent(type, k -> new LRUCache(defaultCapacity));
  }

  public CacheResult read(DataType type, String key) {
    try {
      LRUCache typeCache = typeCaches.get(type);
      if (typeCache == null) {
        return CacheResult.error("Cache for type " + type + " not found");
      }

      Object value = typeCache.get(key);
      if (value == null) {
        return CacheResult.error("Key " + key + " not found in type " + type);
      }

      return CacheResult.success(value);
    } catch (Exception e) {
      return CacheResult.error("Error reading cache: " + e.getMessage());
    }
  }

  public CacheResult post(DataType type, String key, Object value) {
    try {
      LRUCache typeCache = getOrCreateTypeCache(type);

      if (typeCache.containsKey(key)) {
        return CacheResult.error("Key " + key + " already exists in type " + type);
      }

      typeCache.put(key, value);
      return CacheResult.success("Inserted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error inserting cache: " + e.getMessage());
    }
  }

  public CacheResult put(DataType type, String key, Object value) {
    try {
      LRUCache typeCache = getOrCreateTypeCache(type);
      if (!typeCache.containsKey(key)) {
        return CacheResult.error("No value by key: " + key);
      }
      typeCache.put(key, value);
      return CacheResult.success("Put successfully");
    } catch (Exception e) {
      return CacheResult.error("Error putting cache: " + e.getMessage());
    }
  }

  public CacheResult delete(DataType type, String key) {
    try {
      LRUCache typeCache = typeCaches.get(type);
      if (typeCache == null) {
        return CacheResult.error("Cache for type " + type + " not found");
      }

      if (!typeCache.containsKey(key)) {
        return CacheResult.error("Key " + key + " not found in type " + type);
      }

      typeCache.remove(key);
      return CacheResult.success("Deleted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error deleting cache: " + e.getMessage());
    }
  }

  // Геттеры и сеттеры
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getStorageToken() {
    return storageToken;
  }

  public void setStorageToken(String storageToken) {
    this.storageToken = storageToken;
  }

  public Set<DataType> getCacheTypes() {
    return typeCaches.keySet();
  }

  public Cache getCacheByType(DataType type) {
    return typeCaches.get(type);
  }
}