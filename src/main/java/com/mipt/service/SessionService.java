package com.mipt.service;

import com.mipt.model.Session;
import com.mipt.model.User;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class SessionService {
  private final Map<String, Session> activeSessions; // sessionToken -> Session
  private final long sessionTtlHours;

  public SessionService() {
    this.activeSessions = new ConcurrentHashMap<>();

    // Загружаем TTL из properties
    Properties props = new Properties();
    try (InputStream input = SessionService.class
        .getClassLoader()
        .getResourceAsStream("application.properties")) {

      if (input != null) {
        props.load(input);
      }
    } catch (Exception e) {
      // Если файл не найден, используем значение по умолчанию
    }

    // Получаем значение из properties или используем значение по умолчанию
    this.sessionTtlHours = Long.parseLong(
        props.getProperty("session.ttl.hours", "24")
    );
  }

  /**
   * Создает сессию для существующего хранилища
   * Если есть активная сессия для этого хранилища - обновляет ее
   */
  public String createSessionForUser(User user) {
    // Ищем активную сессию для этого юзера
    Optional<String> existingSession = findActiveSessionForUser(user);

    if (existingSession.isPresent()) {
      // Освежаем существующую сессию
      String sessionToken = existingSession.get();
      refreshSession(sessionToken);
      return sessionToken;
    } else {
      // Создаем новую сессию
      String sessionToken = TokenGenerators.generateSessionToken();
      Session session = new Session(user, user.getPermissionType());

      activeSessions.put(sessionToken, session);

      return sessionToken;
    }
  }

  /**
   * Получает валидную сессию
   */
  public Optional<Session> getValidSession(String sessionToken) {
    Session session = activeSessions.get(sessionToken);
    if (session != null && session.isValid(sessionTtlHours)) {
      return Optional.of(session);
    }
    return Optional.empty();
  }

  /**
   * Проверяет валидность сессии
   */
  public boolean isSessionValid(String sessionToken) {
    Session session = activeSessions.get(sessionToken);
    if (session != null) {
      long ttl = sessionTtlHours * 60;
      return !session.isValid(ttl);
    }
    return true;
  }

  /**
   * Очищает просроченные сессии и соответствующие хранилища
   * @return количество удаленных сессий
   */
  public int cleanupExpiredSessions() {
    int removedCount = 0;
    for (String sessionToken : activeSessions.keySet()) {
      if (isSessionValid(sessionToken)) {
        activeSessions.remove(sessionToken);
        removedCount++;
      }
    }
    return removedCount;
  }

  /**
   * Обновляет время сессии
   */
  public void refreshSession(String sessionToken) {
    getValidSession(sessionToken).ifPresent(Session::refresh);
  }

  /**
   * Находит активную сессию для хранилища
   */
  private Optional<String> findActiveSessionForUser(User targetUser) {
    return activeSessions.entrySet().stream()
        .filter(entry -> {
          Session session = entry.getValue();
          User user = session.getCreator();
          return session.isValid(sessionTtlHours) &&
              user != null &&
              user.getUsername().equals(targetUser.getUsername());
        })
        .map(Map.Entry::getKey)
        .findFirst();
  }
}