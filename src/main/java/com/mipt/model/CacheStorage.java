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

  // Конструктор
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

  // Основные операции с валидацией данных
  public CacheResult set(String key, Object value, DataType dataType,
      String user, Long ttlSeconds, long sizeBytes) {

    // ВАЛИДАЦИЯ ДАННЫХ
    CacheResult validationResult = validateData(value, dataType);
    if (!validationResult.isSuccess()) {
      return validationResult;
    }

    // Обрабатываем данные для хранения
    Object processedValue = processValueForStorage(value, dataType);
    if (processedValue == null) {
      return CacheResult.error("Failed to process data for storage");
    }

    if (sizeBytes > maxMemoryBytes) {
      return CacheResult.error("Data exceeding the memory limit");
    }

    try {
      // Проверяем доступность памяти
      if (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
        switch (maxMemoryPolicy) {
          case NOEVICTION -> {
            return CacheResult.error("Memory overflow");
          }
          case ALLKEYSLRU -> {
            while (memoryUsed.get() + sizeBytes > maxMemoryBytes) {
              Object keyToDelete = mainCache.freeMemory();
              CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
              memoryUsed.addAndGet(-(cacheEntry.getSizeInBytes()));
              if (cacheEntry.dataWithTtl()) {
                ttlCache.remove(keyToDelete);
              }
              mainCache.remove(keyToDelete);
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
              CacheEntry cacheEntry = (CacheEntry) mainCache.get(keyToDelete);
              memoryUsed.addAndGet(-(cacheEntry.getSizeInBytes()));
              if (cacheEntry.dataWithTtl()) {
                ttlCache.remove(keyToDelete);
              }
              ;
              mainCache.remove(keyToDelete);
            }
          }
        }
      }

      // Создаем CacheEntry
      CacheEntry entry = new CacheEntry(
          dataType, processedValue, sizeBytes, user, ttlSeconds
      );

      // Сохраняем в основной кэш
      mainCache.put(key, entry);
      memoryUsed.addAndGet(sizeBytes);

      // Если есть TTL, сохраняем в TTL кэш
      if (ttlSeconds != null && ttlSeconds > 0) {
        ttlCache.put(key, entry);
      }

      return CacheResult.success("OK");
    } catch (Exception e) {
      return CacheResult.error("SET error: " + e.getMessage());
    }
  }

  public CacheResult get(String key) {
    try {
      // Проверяем наличие в TTL кэше (быстрая проверка)
      CacheEntry ttlEntry = (CacheEntry) ttlCache.get(key);
      if (ttlEntry != null && ttlEntry.isExpired()) {
        // Удаляем просроченный ключ
        delete(key);
        return CacheResult.error("Key expired");
      }

      // Получаем из основного кэша
      CacheEntry entry = (CacheEntry) mainCache.get(key);
      if (entry == null) {
        return CacheResult.error("Key not found");
      }

      // Обновляем статистику
      entry.incrementAccessCount();

      // Форматируем данные для ответа в зависимости от типа
      String formattedData = formatDataForResponse(entry.getData(), entry.getDataType());
      return CacheResult.success(formattedData);
    } catch (Exception e) {
      return CacheResult.error("GET error: " + e.getMessage());
    }
  }

  // Вспомогательные методы для валидации данных
  private CacheResult validateData(Object value, DataType dataType) {
    if (value == null) {
      return CacheResult.error("Data cannot be null");
    }

    // Проверяем строковые данные
    if (value instanceof String) {
      String stringValue = (String) value;

      // Проверяем валидность в зависимости от типа
      boolean isValid = DataTypeValidator.validateDataByType(
          stringValue,
          dataType.getValue()
      );

      if (!isValid) {
        String errorMessage = getValidationErrorMessage(stringValue, dataType);
        return CacheResult.error(errorMessage);
      }
    }
    // Для byte[] не нужна дополнительная валидация - это уже бинарные данные
    else if (value instanceof byte[]) {
      // byte[] всегда валидны для типа BYTES
      if (dataType != DataType.BYTES) {
        return CacheResult.error("byte[] data can only be used with BYTES data type");
      }
    }
    else {
      return CacheResult.error("Unsupported data type: " + value.getClass().getName());
    }

    return CacheResult.success();
  }

  private Object processValueForStorage(Object value, DataType dataType) {
    try {
      if (value instanceof String) {
        return DataTypeValidator.processDataForStorage((String) value, dataType.getValue());
      }
      // byte[] уже обработаны, возвращаем как есть
      else if (value instanceof byte[]) {
        return value;
      }
    } catch (IllegalArgumentException e) {
      return null;
    }

    return value;
  }

  private String getValidationErrorMessage(String data, DataType dataType) {
    switch (dataType) {
      case JSON:
        return "Invalid JSON format. Please provide valid JSON data.";
      case BYTES:
        return "Invalid Base64 format. Please provide valid Base64 encoded data.";
      case STRING:
        if (data == null || data.trim().isEmpty()) {
          return "String data cannot be null or empty.";
        }
        return "Invalid string data.";
      default:
        return "Invalid data format for type: " + dataType.getValue();
    }
  }

  private String formatDataForResponse(Object data, DataType dataType) {
    try {
      return DataTypeValidator.formatDataForResponse(data, dataType.getValue());
    } catch (Exception e) {
      // В случае ошибки форматирования, возвращаем строковое представление
      return data != null ? data.toString() : "";
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

  // Геттеры
  public long getMemoryUsed() {
    return memoryUsed.get();
  }

  public long getMaxMemory() {
    return maxMemoryBytes;
  }

  // Метод для получения типа данных по ключу
  public DataType getDataType(String key) {
    CacheEntry entry = (CacheEntry) mainCache.get(key);
    return entry != null ? entry.getDataType() : null;
  }
}