package com.mipt.cache;

public class FileDistribute {
  private final CacheStorage cacheStorage;

  public FileDistribute() {
    this.cacheStorage = new CacheStorage();
  }

  public FileDistribute(int capacity) {
    this.cacheStorage = new CacheStorage(capacity);
  }

  public CacheResult readCache(String storageToken, String key, String type) {
    // Здесь можно добавить валидацию storageToken если нужно
    return cacheStorage.read(type, key);
  }

  public CacheResult insertCache(String storageToken, String key, String type, String value) {
    // Валидация storageToken
    if (!validateStorageToken(storageToken)) {
      return CacheResult.error("Invalid storage token");
    }

    // Преобразование значения в нужный тип
    Object typedValue = convertValue(type, value);
    if (typedValue == null) {
      return CacheResult.error("Unsupported data type: " + type);
    }

    return cacheStorage.insert(type, key, typedValue);
  }

  public CacheResult putCache(String storageToken, String key, String type, String value) {
    // Валидация storageToken
    if (!validateStorageToken(storageToken)) {
      return CacheResult.error("Invalid storage token");
    }

    // Преобразование значения в нужный тип
    Object typedValue = convertValue(type, value);
    if (typedValue == null) {
      return CacheResult.error("Unsupported data type: " + type);
    }

    return cacheStorage.put(type, key, typedValue);
  }

  public CacheResult deleteCache(String storageToken, String key, String type) {
    // Валидация storageToken
    if (!validateStorageToken(storageToken)) {
      return CacheResult.error("Invalid storage token");
    }

    return cacheStorage.delete(type, key);
  }

  private boolean validateStorageToken(String storageToken) {
    // TODO: Реализовать логику валидации токена
    return storageToken != null && !storageToken.trim().isEmpty();
  }

  private Object convertValue(String type, String value) {
    try {
      return switch (type.toLowerCase()) {
        case "string" -> value;
        case "integer", "int" -> Integer.parseInt(value);
        case "long" -> Long.parseLong(value);
        case "double" -> Double.parseDouble(value);
        case "float" -> Float.parseFloat(value);
        case "boolean", "bool" -> Boolean.parseBoolean(value);
        default -> null;
      };
    } catch (NumberFormatException e) {
      return null;
    }
  }


}
