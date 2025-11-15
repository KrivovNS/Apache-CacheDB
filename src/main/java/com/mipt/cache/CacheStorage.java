package com.mipt.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStorage {
  private final Map<String, LRUCache<String, Object>> typeCaches;
  private final int defaultCapacity;

  public CacheStorage(int defaultCapacity) {
    this.defaultCapacity = defaultCapacity;
    this.typeCaches = new ConcurrentHashMap<>();
  }

  public CacheStorage() {
    this(1000); // дефолтная емкость
  }

  // Получаем или создаем кэш для конкретного типа данных
  private LRUCache<String, Object> getOrCreateTypeCache(String type) {
    return typeCaches.computeIfAbsent(type, k -> new LRUCache<>(defaultCapacity));
  }

  // Чтение данных
  public CacheResult read(String type, String key) {
    try {
      LRUCache<String, Object> typeCache = typeCaches.get(type);
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

  // Вставка данных (только если ключ не существует)
  public CacheResult insert(String type, String key, Object value) {
    try {
      LRUCache<String, Object> typeCache = getOrCreateTypeCache(type);

      if (typeCache.containsKey(key)) {
        return CacheResult.error("Key " + key + " already exists in type " + type);
      }

      typeCache.put(key, value);
      return CacheResult.success("Inserted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error inserting cache: " + e.getMessage());
    }
  }

  // Обновление или создание данных
  public CacheResult put(String type, String key, Object value) {
    try {
      LRUCache<String, Object> typeCache = getOrCreateTypeCache(type);
      typeCache.put(key, value);
      return CacheResult.success("Put successfully");
    } catch (Exception e) {
      return CacheResult.error("Error putting cache: " + e.getMessage());
    }
  }

  // Удаление данных
  public CacheResult delete(String type, String key) {
    try {
      LRUCache<String, Object> typeCache = typeCaches.get(type);
      if (typeCache == null) {
        return CacheResult.error("Cache for type " + type + " not found");
      }

      Object removedValue = typeCache.remove(key);
      if (removedValue == null) {
        return CacheResult.error("Key " + key + " not found in type " + type);
      }

      return CacheResult.success("Deleted successfully");
    } catch (Exception e) {
      return CacheResult.error("Error deleting cache: " + e.getMessage());
    }
  }

  // Получение статистики
  public Map<String, Integer> getStats() {
    Map<String, Integer> stats = new ConcurrentHashMap<>();
    typeCaches.forEach((type, cache) -> {
      stats.put(type, cache.size());
    });
    return stats;
  }
}
