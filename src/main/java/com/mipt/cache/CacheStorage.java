package com.mipt.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStorage {
  private final Map<String, LRUCache> typeCaches;
  private final int defaultCapacity;

  public CacheStorage(int defaultCapacity) {
    this.defaultCapacity = defaultCapacity;
    this.typeCaches = new ConcurrentHashMap<>();
  }

  public CacheStorage() {
    this(1000);
  }

  private LRUCache getOrCreateTypeCache(String type) {
    return typeCaches.computeIfAbsent(type, k -> new LRUCache(defaultCapacity));
  }

  public CacheResult read(String type, String key) {
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

  public CacheResult insert(String type, String key, Object value) {
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

  public CacheResult put(String type, String key, Object value) {
    try {
      LRUCache typeCache = getOrCreateTypeCache(type);
      typeCache.put(key, value);
      return CacheResult.success("Put successfully");
    } catch (Exception e) {
      return CacheResult.error("Error putting cache: " + e.getMessage());
    }
  }

  public CacheResult delete(String type, String key) {
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

  public Map<String, Integer> getStats() {
    Map<String, Integer> stats = new ConcurrentHashMap<>();
    typeCaches.forEach((type, cache) -> {
      stats.put(type, cache.size());
    });
    return stats;
  }
}