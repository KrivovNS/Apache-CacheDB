package com.mipt.service;

import com.mipt.model.Session;
import com.mipt.model.CacheStorage;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SessionService {
  private final CacheStorageService cacheStorageService;
  private final Map<String, Session> activeSessions; // sessionToken -> Session
  private static final long SESSION_TTL_HOURS = 24;
  private static long TEST_TTL_MINUTES = 1; // 1 минута для тестов

  public SessionService(CacheStorageService cacheStorageService) {
    this.cacheStorageService = cacheStorageService;
    this.activeSessions = new ConcurrentHashMap<>();
  }

  /**
   * Создает новую сессию для нового хранилища
   */
  public String createNewSession() {
    CacheStorage storage = cacheStorageService.createNewStorage();
    String sessionToken = TokenGenerators.generateSessionToken();
    Session session = new Session(sessionToken, storage);

    activeSessions.put(sessionToken, session);
    cacheStorageService.registerSession(sessionToken, storage);

    return sessionToken;
  }

  /**
   * Создает сессию для существующего хранилища
   * Если есть активная сессия для этого хранилища - обновляет ее
   */
  public String createSessionForStorage(String storageToken) {
    // Ищем активную сессию для этого хранилища
    Optional<String> existingSession = findActiveSessionForStorage(storageToken);

    if (existingSession.isPresent()) {
      // Освежаем существующую сессию
      String sessionToken = existingSession.get();
      refreshSession(sessionToken);
      return sessionToken;
    } else {
      // Создаем новую сессию
      CacheStorage storage = cacheStorageService.loadStorage(storageToken);
      String sessionToken = TokenGenerators.generateSessionToken();
      Session session = new Session(sessionToken, storage);

      activeSessions.put(sessionToken, session);
      cacheStorageService.registerSession(sessionToken, storage);

      return sessionToken;
    }
  }

  /**
   * Получает валидную сессию
   */
  public Optional<Session> getValidSession(String sessionToken) {
    Session session = activeSessions.get(sessionToken);
    if (session != null && session.isValid(SESSION_TTL_HOURS)) {
      return Optional.of(session);
    }
    return Optional.empty();
  }

  /**
   * Получает хранилище по валидной сессии
   */
  public Optional<CacheStorage> getStorageBySession(String sessionToken) {
    return getValidSession(sessionToken).map(Session::getStorage);
  }

  /**
   * Проверяет валидность сессии
   */
  public boolean isSessionValid(String sessionToken) {
    Session session = activeSessions.get(sessionToken);
    if (session != null) {
      // Используем тестовый TTL если он установлен
      long ttl = (TEST_TTL_MINUTES > 0) ? TEST_TTL_MINUTES : SESSION_TTL_HOURS * 60;
      return session.isValid(ttl);
    }
    return false;
  }

  /**
   * Обновляет время сессии
   */
  public void refreshSession(String sessionToken) {
    getValidSession(sessionToken).ifPresent(Session::refresh);
  }

  /**
   * Очищает просроченные сессии и соответствующие хранилища
   * @return количество удаленных сессий
   */
  public int cleanupExpiredSessions() {
    int removedCount = 0;
    for (String sessionToken : activeSessions.keySet()) {
      if (!isSessionValid(sessionToken)) {
        activeSessions.remove(sessionToken);
        cacheStorageService.unregisterSession(sessionToken);
        removedCount++;
      }
    }
    return removedCount;
  }

  /**
   * Находит активную сессию для хранилища
   */
  private Optional<String> findActiveSessionForStorage(String storageToken) {
    return activeSessions.entrySet().stream()
        .filter(entry -> {
          Session session = entry.getValue();
          CacheStorage storage = session.getStorage();
          return session.isValid(SESSION_TTL_HOURS) &&
              storage != null &&
              storageToken.equals(storage.getStorageToken());
        })
        .map(Map.Entry::getKey)
        .findFirst();
  }
}