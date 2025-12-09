package com.mipt.model;

import com.mipt.cache.*;
import com.mipt.controller.DataTypeValidator;
import java.util.concurrent.atomic.AtomicLong;

public class CacheStorage {

  private final Cache mainCache;      // Основное хранилище (LRU или Simple)
  private final Cache ttlCache;       // Хранилище для ключей с TTL
  private final AtomicLong memoryUsed;
  private final long maxMemoryBytes;
  private final MaxMemoryPolicy maxMemoryPolicy;

  public CacheStorage(long maxMemoryBytes, MaxMemoryPolicy maxMemoryPolicy) {
    this.maxMemoryBytes = maxMemoryBytes;
    this.memoryUsed = new AtomicLong(0);
    this.maxMemoryPolicy = maxMemoryPolicy;
    CacheType cacheType;
    switch (maxMemoryPolicy) {
      case NOEVICTION -> cacheType = CacheType.SIMPLE;
      case ALLKEYSLRU, VOLATILELRU -> cacheType = CacheType.LRU;
      case ALLKEYSLFU -> cacheType = CacheType.LFU;
      default -> cacheType = CacheType.LRU;
    }
    this.mainCache = createCache(cacheType);
    this.ttlCache = createCache(cacheType);
  }

  private Cache createCache(CacheType type) {
    return switch (type) {
      case LRU -> new LRUCache();
      case SIMPLE -> new SimpleCache();
      case LFU -> new LFUCache();
    };
  }

  // Основные операции
  public CacheResult set(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {

    try {
      String dataString;
      if (value == null) {
        return CacheResult.error("Value cannot be null");
      } else if (value instanceof String) {
        dataString = (String) value;
      } else if (value instanceof byte[]) {
        // Для байтового массива преобразуем в Base64 для валидации
        dataString = java.util.Base64.getEncoder().encodeToString((byte[]) value);
      } else {
        dataString = value.toString();
      }

      if (!DataTypeValidator.validateDataByType(dataString, dataType.getValue())) {
        return CacheResult.error("Invalid data format for type: " + dataType);
      }

      // Преобразование данных для хранения
      Object processedValue;
      try {
        processedValue = DataTypeValidator.processDataForStorage(
            dataString,
            dataType.getValue()
        );
      } catch (IllegalArgumentException e) {
        return CacheResult.error("Data processing error: " + e.getMessage());
      }

      if (sizeBytes > maxMemoryBytes) {
        return CacheResult.error("Data exceeding the memory limit");
      }

      try {
        if (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
          switch (maxMemoryPolicy) {
            case NOEVICTION -> {
              return CacheResult.error("Memory overflow");
            }
            case ALLKEYSLRU -> {
              while (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
                Object keyToDelete = mainCache.freeMemory();
                if (keyToDelete == null) break;

                CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
                if (cacheEntry != null) {
                  memoryUsed.addAndGet(-cacheEntry.getSizeInBytes());
                  if (cacheEntry.dataWithTtl()) {
                    ttlCache.remove(keyToDelete);
                  }
                  mainCache.remove(keyToDelete);
                }
              }
            }
            case VOLATILELRU -> {
              while (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
                Object keyToDelete;
                if (ttlCache.size() > 0) {
                  keyToDelete = ttlCache.freeMemory();
                } else {
                  keyToDelete = mainCache.freeMemory();
                }

                if (keyToDelete == null) break;

                CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
                if (cacheEntry != null) {
                  memoryUsed.addAndGet(-cacheEntry.getSizeInBytes());
                  if (cacheEntry.dataWithTtl()) {
                    ttlCache.remove(keyToDelete);
                  }
                  mainCache.remove(keyToDelete);
                }
              }
            }
          }

          if (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
            return CacheResult.error("Not enough memory even after eviction");
          }
        }

        CacheEntry entry = new CacheEntry(
            dataType, processedValue, sizeBytes, user, ttlSeconds
        );

        mainCache.put(key, entry);
        memoryUsed.addAndGet(sizeBytes);

        if (ttlSeconds != null && ttlSeconds > 0) {
          ttlCache.put(key, entry);
        }

        return CacheResult.success("OK");
      } catch (Exception e) {
        return CacheResult.error("SET error: " + e.getMessage());
      }
    } catch (Exception e) {
      return CacheResult.error("SET error: " + e.getMessage());
    }
  }

  public CacheResult get(String key) {
    try {
      CacheEntry ttlEntry = (CacheEntry) ttlCache.get(key);
      if (ttlEntry != null && ttlEntry.isExpired()) {
        delete(key);
        return CacheResult.error("Key expired");
      }

      CacheEntry entry = (CacheEntry) mainCache.get(key);
      if (entry == null) {
        return CacheResult.error("Key not found");
      }

      entry.incrementAccessCount();

      String formattedData;
      try {
        formattedData = DataTypeValidator.formatDataForResponse(
            entry.getData(),
            entry.getDataType().getValue()
        );
      } catch (IllegalArgumentException e) {
        return CacheResult.error("Data formatting error: " + e.getMessage());
      }

      return CacheResult.success(formattedData);
    } catch (Exception e) {
      return CacheResult.error("GET error: " + e.getMessage());
    }
  }

  public CacheResult delete(String key) {
    try {
      CacheEntry entry = (CacheEntry) mainCache.get(key);
      if (entry != null) {
        mainCache.remove(key);
        ttlCache.remove(key);
        memoryUsed.addAndGet(-entry.getSizeInBytes());
        return CacheResult.success("Deleted");
      }
      return CacheResult.error("Key not found");
    } catch (Exception e) {
      return CacheResult.error("DEL error: " + e.getMessage());
    }
  }

  public boolean containsKey(String key) {
    return mainCache.containsKey(key);
  }

  // Методы для очистки просроченных ключей
  public int cleanupExpired() {
    int removed = 0;
    // Проходим по TTL кэшу
    for (Object key : ttlCache.getKeys()) {
      CacheEntry entry = (CacheEntry) ttlCache.get(key);
      if (entry != null && entry.isExpired()) {
        delete((String) key);
        removed++;
      }
    }
    return removed;
  }

  public long getMemoryUsed() {
    return memoryUsed.get();
  }

  public long getMaxMemory() {
    return maxMemoryBytes;
  }

  public DataType getDataType(String key) {
    CacheEntry entry = (CacheEntry) mainCache.get(key);
    return entry != null ? entry.getDataType() : null;
  }
}