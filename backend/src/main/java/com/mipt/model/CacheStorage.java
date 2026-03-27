package com.mipt.model;

import com.mipt.cache.Cache;
import com.mipt.cache.CacheResult;
import com.mipt.cache.LFUCache;
import com.mipt.cache.LRUCache;
import com.mipt.cache.SimpleCache;
import com.mipt.controller.DataTypeValidator;
import com.mipt.controller.MemoryCalculator;
import com.mipt.database.dao.CacheEntryDAO;
import java.util.concurrent.atomic.AtomicLong;

public class CacheStorage {

  private final Cache mainCache;
  private final Cache ttlCache;
  private final AtomicLong memoryUsed;
  private final long maxMemoryBytes;
  private final MaxMemoryPolicy maxMemoryPolicy;
  private final boolean persistance;
  private final CacheEntryDAO cacheEntryDAO;

  public CacheStorage(long maxMemoryBytes, MaxMemoryPolicy maxMemoryPolicy, boolean persistance,
      CacheEntryDAO cacheEntryDAO) {
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
    this.persistance = persistance;
    this.cacheEntryDAO = cacheEntryDAO;
  }

  private Cache createCache(CacheType type) {
    return switch (type) {
      case LRU -> new LRUCache();
      case SIMPLE -> new SimpleCache();
      case LFU -> new LFUCache();
    };
  }

  // Kept for backward compatibility; sizeBytes is ignored.
  public CacheResult set(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes, HttpMethod httpMethod) {
    return set(key, value, dataType, user, ttlSeconds, httpMethod);
  }

  public CacheResult set(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, HttpMethod httpMethod) {
    boolean isUpdate = httpMethod == HttpMethod.PUT;
    try {
      String dataString;
      if (value == null) {
        return CacheResult.error("Value cannot be null");
      } else if (value instanceof String) {
        dataString = (String) value;
      } else if (value instanceof byte[]) {
        dataString = java.util.Base64.getEncoder().encodeToString((byte[]) value);
      } else {
        dataString = value.toString();
      }

      if (!DataTypeValidator.validateDataByType(dataString, dataType.getValue())) {
        return CacheResult.error("Invalid data format for type: " + dataType);
      }

      Object processedValue;
      try {
        processedValue = DataTypeValidator.processDataForStorage(dataString, dataType.getValue());
      } catch (IllegalArgumentException e) {
        return CacheResult.error("Data processing error: " + e.getMessage());
      }

      long entrySizeBytes;
      try {
        entrySizeBytes = MemoryCalculator.calculateEntrySizeBytes(key, dataType, processedValue);
      } catch (IllegalArgumentException e) {
        return CacheResult.error("Size calculation error: " + e.getMessage());
      }

      if (entrySizeBytes > maxMemoryBytes) {
        return CacheResult.error("Data exceeding the memory limit");
      }

      try {
        CacheEntry existingEntry = (CacheEntry) mainCache.get(key);
        long existingSize = 0;

        if (existingEntry != null && isUpdate) {
          existingSize = existingEntry.getSizeInBytes();
        }
        long netSizeChange = isUpdate ? entrySizeBytes - existingSize : entrySizeBytes;

        if (memoryUsed.get() + netSizeChange > maxMemoryBytes) {
          switch (maxMemoryPolicy) {
            case NOEVICTION -> {
              return CacheResult.error("Memory overflow");
            }
            case ALLKEYSLRU, ALLKEYSLFU -> {
              while (memoryUsed.get() + netSizeChange > maxMemoryBytes) {
                Object keyToDelete = mainCache.freeMemory();
                if (keyToDelete == null) {
                  break;
                }

                CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
                if (cacheEntry != null) {
                  this.delete((String) keyToDelete);
                }
              }
            }
            case VOLATILELRU, VOLATILELFU -> {
              while (memoryUsed.get() + netSizeChange > maxMemoryBytes) {
                Object keyToDelete;
                if (ttlCache.size() > 0) {
                  keyToDelete = ttlCache.freeMemory();
                } else {
                  keyToDelete = mainCache.freeMemory();
                }

                if (keyToDelete == null) {
                  break;
                }

                CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
                if (cacheEntry != null) {
                  this.delete((String) keyToDelete);
                }
              }
            }
          }

          if (memoryUsed.get() + netSizeChange > maxMemoryBytes) {
            return CacheResult.error("Not enough memory even after eviction");
          }
        }

        if (isUpdate && existingEntry != null) {
          memoryUsed.addAndGet(-existingEntry.getSizeInBytes());
          if (existingEntry.getExpiresAt() != null) {
            ttlCache.remove(key);
          }
        }

        CacheEntry entry = new CacheEntry(dataType, processedValue, entrySizeBytes, user, ttlSeconds);
        mainCache.put(key, entry);
        memoryUsed.addAndGet(entrySizeBytes);

        if (ttlSeconds != null && ttlSeconds > 0) {
          ttlCache.put(key, entry);
        }

        if (persistance) {
          cacheEntryDAO.saveCacheEntry(key, entry);
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

      String formattedData;
      try {
        formattedData = DataTypeValidator.formatDataForResponse(entry.getData(), entry.getDataType().getValue());
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
        if (persistance) {
          cacheEntryDAO.deleteCacheEntry(key, entry.getDataType());
        }
        return CacheResult.success("Deleted");
      }
      return CacheResult.error("Key not found");
    } catch (Exception e) {
      return CacheResult.error("DEL error: " + e.getMessage());
    }
  }

  public void restoreEntry(String key, CacheEntry entry) {
    mainCache.put(key, entry);
    memoryUsed.addAndGet(entry.getSizeInBytes());
    if (entry.dataWithTtl() && !entry.isExpired()) {
      ttlCache.put(key, entry);
    }
  }

  public boolean containsKey(String key) {
    return mainCache.containsKey(key);
  }

  public int cleanupExpired() {
    int removed = 0;
    for (Object key : ttlCache.getKeys()) {
      CacheEntry entry = (CacheEntry) ttlCache.get(key);
      if (entry != null && entry.isExpired()) {
        delete((String) key);
        removed++;
      }
    }
    return removed;
  }

  public long getMemoryUsedBytes() {
    return memoryUsed.get();
  }

  public long getMaxMemoryBytes() {
    return maxMemoryBytes;
  }
}
