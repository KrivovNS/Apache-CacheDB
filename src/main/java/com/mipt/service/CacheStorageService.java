package com.mipt.service;

import com.mipt.cache.CacheResult;
import com.mipt.controller.DataType;
import com.mipt.database.dao.CacheStorageDAO;
import com.mipt.model.CacheStorage;
import com.mipt.model.CacheStorage.CacheType;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStorageService {
  private final CacheStorageDAO cacheStorageDAO;
  private final Map<String, CacheStorage> sessionStorages;
  private final long defaultMaxMemoryBytes;
  private final CacheType defaultCacheType;

  public CacheStorageService(CacheStorageDAO cacheStorageDAO) {
    this(cacheStorageDAO, 100 * 1024 * 1024, CacheType.LRU); // 100MB по умолчанию
  }

  public CacheStorageService(CacheStorageDAO cacheStorageDAO,
      long defaultMaxMemoryBytes,
      CacheType defaultCacheType) {
    this.cacheStorageDAO = cacheStorageDAO;
    this.sessionStorages = new ConcurrentHashMap<>();
    this.defaultMaxMemoryBytes = defaultMaxMemoryBytes;
    this.defaultCacheType = defaultCacheType;
  }

  public void registerSession(String sessionToken, CacheStorage storage) {
    sessionStorages.put(sessionToken, storage);
  }

  public void unregisterSession(String sessionToken) {
    CacheStorage storage = sessionStorages.remove(sessionToken);
    if (storage != null) {
      cacheStorageDAO.saveDataFromCacheInDatabase(storage);
      storage.shutdown();
    }
  }

  public CacheStorage createNewStorage(CacheType cacheType, Long maxMemoryBytes) {
    String storageToken = TokenGenerators.generateStorageToken(32);
    long memory = maxMemoryBytes != null ? maxMemoryBytes : defaultMaxMemoryBytes;
    CacheType type = cacheType != null ? cacheType : defaultCacheType;

    CacheStorage storage = new CacheStorage(memory, type);
    storage.setStorageToken(storageToken);
    return storage;
  }

  // CRUD операции
  public CacheResult putData(String sessionToken, String key, DataType dataType,
      Object data, String user, Long ttlMillis, Integer segment) {
    return getStorageBySession(sessionToken)
        .map(storage -> storage.put(key, dataType, data, user, ttlMillis, segment))
        .orElse(CacheResult.error("Invalid session"));
  }

  public CacheResult getData(String sessionToken, String key) {
    return getStorageBySession(sessionToken)
        .map(storage -> storage.get(key))
        .orElse(CacheResult.error("Invalid session"));
  }

  public CacheResult deleteData(String sessionToken, String key) {
    return getStorageBySession(sessionToken)
        .map(storage -> storage.delete(key))
        .orElse(CacheResult.error("Invalid session"));
  }

  public CacheResult getStats(String sessionToken) {
    return getStorageBySession(sessionToken)
        .map(storage -> CacheResult.success(storage.getStats()))
        .orElse(CacheResult.error("Invalid session"));
  }

  public Optional<CacheStorage> getStorageBySession(String sessionToken) {
    return Optional.ofNullable(sessionStorages.get(sessionToken));
  }
}