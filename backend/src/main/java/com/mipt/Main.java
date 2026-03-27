package com.mipt;

import com.mipt.database.dao.UserDAO;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.database.initialization.DatabaseInitializer;
import com.mipt.server.NettyHttpServer;
import com.mipt.server.NettyTcpServer;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.telemetry.TelemetryService;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) {
    TelemetryService telemetryService = null;
    try {
      Properties properties = loadApplicationProperties();
      int port = parseHttpPort(properties);

      log.info("Server starting on port: {}", port);
      log.info("Initializing database...");
      DatabaseInitializer.initializeDatabase();

      UserDAO userDAO = new UserDAO();
      CacheStorageService cacheService = new CacheStorageService();
      telemetryService = new TelemetryService(properties, cacheService);
      SessionService sessionService = new SessionService();

      Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
        int removedSessions = sessionService.cleanupExpiredSessions();
        if (removedSessions > 0) {
          log.info("Cleanup: {} expired sessions removed", removedSessions);
        }
      }, 1, 1, TimeUnit.MINUTES);

      NettyTcpServer tcpServer = new NettyTcpServer(
          9090, cacheService, sessionService, userDAO, telemetryService
      );
      new Thread(() -> {
        try {
          tcpServer.run();
        } catch (Exception e) {
          log.error("TCP server error", e);
        }
      }, "tcp-server").start();

      NettyHttpServer httpServer = new NettyHttpServer(
          port, cacheService, sessionService, userDAO, telemetryService
      );
      httpServer.run();
    } catch (Exception e) {
      log.error("Failed to start application", e);
    } finally {
      if (telemetryService != null) {
        telemetryService.close();
      }
      DatabaseConnection.closeConnection();
    }
  }

  private static Properties loadApplicationProperties() {
    Properties properties = new Properties();
    try (InputStream stream = Main.class.getClassLoader().getResourceAsStream("application.properties")) {
      if (stream != null) {
        properties.load(stream);
      }
    } catch (Exception e) {
      log.warn("Could not load application.properties, default values will be used");
    }
    return properties;
  }

  private static int parseHttpPort(Properties properties) {
    try {
      return Integer.parseInt(properties.getProperty("server.port", "8080"));
    } catch (Exception exception) {
      log.warn("Could not read server.port from config, using default 8080");
      return 8080;
    }
  }
}
