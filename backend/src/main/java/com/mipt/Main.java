package com.mipt;

import com.mipt.database.initialization.DatabaseInitializer;
import com.mipt.database.dao.*;
import com.mipt.server.NettyHttpServer;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) {
    try {
      // 1. Загружаем порт из конфигурации
      int port = 7070;
      try {
        Properties props = new Properties();
        props.load(Main.class.getClassLoader()
            .getResourceAsStream("application.properties"));
        port = Integer.parseInt(props.getProperty("server.port", "7070"));
      } catch (Exception e) {
        log.warn("Could not read config, using default port 7070");
      }

      log.info("Server starting on port: {}", port);

      // 2. Инициализируем базу данных
      log.info("Initializing database...");
      DatabaseInitializer.initializeDatabase();

      // 3. Инициализируем DAO
      UserDAO userDAO = new UserDAO();

      // 4. Инициализируем сервисы
      CacheStorageService cacheService = new CacheStorageService();
      SessionService sessionService = new SessionService();

      // 5. Запускаем очистку сессий каждую минуту
      Executors.newScheduledThreadPool(1)
          .scheduleAtFixedRate(() -> {
            int removedSessions = sessionService.cleanupExpiredSessions();
            if (removedSessions > 0) {
              log.info("Cleanup: {} expired sessions removed", removedSessions);
            }
          }, 1, 1, TimeUnit.MINUTES);

      // 6. Запускаем Netty HTTP сервер
      NettyHttpServer server = new NettyHttpServer(
          port, cacheService, sessionService, userDAO);

      server.run();

    } catch (Exception e) {
      log.error("Failed to start application", e);
    } finally {
      com.mipt.database.initialization.DatabaseConnection.closeConnection();
    }
  }
}
