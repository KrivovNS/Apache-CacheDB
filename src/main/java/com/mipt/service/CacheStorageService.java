package com.mipt.service;

import static com.mipt.service.TokenGenerators.generateStorageToken;

import com.mipt.cache.CacheResult;
import com.mipt.controller.DataType;
import com.mipt.database.dao.CacheStorageDAO;
import com.mipt.model.CacheStorage;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStorageService {
  private final CacheStorageDAO cacheStorageDAO;
  private final Map<String, CacheStorage> sessionStorages; // sessionToken -> CacheStorage

  public CacheStorageService(CacheStorageDAO cacheStorageDAO) {
    this.cacheStorageDAO = cacheStorageDAO;
    this.sessionStorages = new ConcurrentHashMap<>();
  }

  /**
   * Регистрирует сессию с хранилищем
   */
  public void registerSession(String sessionToken, CacheStorage storage) {
    sessionStorages.put(sessionToken, storage);
  }

  /**
   * Удаляет хранилище сессии и сохраняет данные в БД
   */
  public void unregisterSession(String sessionToken) {
    CacheStorage storage = sessionStorages.remove(sessionToken);
    if (storage != null) {
      cacheStorageDAO.saveDataFromCacheInDatabase(storage);
    }
  }

  /**
   * Создает новое хранилище и возвращает его
   */
  public CacheStorage createNewStorage() {
    String storageToken = generateStorageToken(32);
    CacheStorage storage = new CacheStorage(1000);
    storage.setStorageToken(storageToken);
    return storage;
  }

  /**
   * Загружает существующее хранилище из БД
   */
  public CacheStorage loadStorage(String storageToken) {
    CacheStorage storage = cacheStorageDAO.loadDataFromDatabaseByToken(storageToken);
    if (storage == null) {
      // Если не найдено в БД, создаем новое
      storage = new CacheStorage(1000);
      storage.setStorageToken(storageToken);
    }
    return storage;
  }

  /**
   * CRUD операции - работают только с зарегистрированными сессиями
   */
  public CacheResult readData(String sessionToken, DataType dataType, String key) {
    return getStorageBySession(sessionToken)
        .map(storage -> storage.read(dataType, key))
        .orElse(CacheResult.error("Invalid session"));
  }

  public CacheResult insertData(String sessionToken, DataType dataType, String key, Object value) {
    return getStorageBySession(sessionToken)
        .map(storage -> storage.post(dataType, key, value))
        .orElse(CacheResult.error("Invalid session"));
  }

  public CacheResult updateData(String sessionToken, DataType dataType, String key, Object value) {
    return getStorageBySession(sessionToken)
        .map(storage -> storage.put(dataType, key, value))
        .orElse(CacheResult.error("Invalid session"));
  }

  public CacheResult deleteData(String sessionToken, DataType dataType, String key) {
    return getStorageBySession(sessionToken)
        .map(storage -> storage.delete(dataType, key))
        .orElse(CacheResult.error("Invalid session"));
  }

  /**
   * Получает хранилище по токену сессии
   */
  public Optional<CacheStorage> getStorageBySession(String sessionToken) {
    return Optional.ofNullable(sessionStorages.get(sessionToken));
  }

  /**
   * Получает количество активных сессионных хранилищ
   */
  public int getActiveSessionStoragesCount() {
    return sessionStorages.size();
  }
}