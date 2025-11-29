package com.mipt;

import com.mipt.database.initialization.DatabaseInitializer;
import com.mipt.database.dao.*;
import com.mipt.server.NettyHttpServer;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
  public static void main(String[] args) {
    try {
      // 1. Инициализируем базу данных
      System.out.println("Initializing database...");
      DatabaseInitializer.initializeDatabase();

      // 2. Инициализируем DAO
      UserDAO userDAO = new UserDAO();
      CacheStorageDAO cacheStorageDAO = new CacheStorageDAO();

      // 3. Инициализируем сервисы
      CacheStorageService cacheService = new CacheStorageService(cacheStorageDAO);
      SessionService sessionService = new SessionService(cacheService);

      // 4. Запускаем очистку сессий каждую минуту
      Executors.newScheduledThreadPool(1)
          .scheduleAtFixedRate(() -> {
            int removedSessions = sessionService.cleanupExpiredSessions();
            if (removedSessions > 0) {
              System.out.println("Cleanup: " + removedSessions + " expired sessions removed");
            }
          }, 1, 1, TimeUnit.MINUTES);

      // 5. Запускаем Netty HTTP сервер
      int port = 8080;
      NettyHttpServer server = new NettyHttpServer(
          port, cacheService, sessionService, userDAO, cacheStorageDAO
      );

      server.run();

    } catch (Exception e) {
      System.err.println("Failed to start application: " + e.getMessage());
      e.printStackTrace();
    } finally {
      com.mipt.database.initialization.DatabaseConnection.closeConnection();
    }
  }
}