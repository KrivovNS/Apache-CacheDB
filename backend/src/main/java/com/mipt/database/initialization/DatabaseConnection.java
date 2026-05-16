package com.mipt.database.initialization;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseConnection {

  private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);
  private static final String DEFAULT_DB_URL = "jdbc:h2:file:./data/storagedb;DB_CLOSE_DELAY=-1";
  private static final String DEFAULT_DB_USERNAME = "sa";
  private static final String DEFAULT_DB_PASSWORD = "";

  private static JdbcConnectionPool connectionPool;

  public static Connection getConnection() throws SQLException {
    if (connectionPool == null) {
      initializePool();
    }
    return connectionPool.getConnection();
  }

  private static synchronized void initializePool() throws SQLException {
    if (connectionPool != null) {
      return;
    }

    try {
      Properties props = new Properties();

      try (InputStream input = DatabaseConnection.class
          .getClassLoader()
          .getResourceAsStream("application.properties")) {
        if (input != null) {
          props.load(input);
        }
      }

      String url = System.getProperty(
          "db.url",
          props.getProperty("db.url", DEFAULT_DB_URL)
      );
      String username = System.getProperty(
          "db.username",
          props.getProperty("db.username", DEFAULT_DB_USERNAME)
      );
      String password = System.getProperty(
          "db.password",
          props.getProperty("db.password", DEFAULT_DB_PASSWORD)
      );

      if (url == null || url.isBlank()) {
        throw new SQLException("Database URL is not configured");
      }

      try {
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("./data"));
      } catch (Exception e) {
        log.warn("Could not create data directory: {}", e.getMessage());
      }

      String tunedUrl = ensureLockTimeout(url);
      log.info("Initializing database pool: {}", tunedUrl);

      JdbcConnectionPool pool = JdbcConnectionPool.create(tunedUrl, username, password);
      pool.setMaxConnections(64);
      pool.setLoginTimeout(10);

      connectionPool = pool;
      log.info("Database pool initialized successfully");
    } catch (Exception e) {
      log.error("Pool initialization failed", e);
      throw new SQLException("Failed to initialize database pool", e);
    }
  }

  private static String ensureLockTimeout(String url) {
    if (url == null) {
      return null;
    }
    String normalized = url.toUpperCase();
    if (normalized.contains("LOCK_TIMEOUT=")) {
      return url;
    }
    return url + ";LOCK_TIMEOUT=10000";
  }

  public static synchronized void closeConnection() {
    if (connectionPool != null) {
      try {
        connectionPool.dispose();
        log.info("Database pool closed");
      } catch (Exception e) {
        log.error("Error closing database pool", e);
      } finally {
        connectionPool = null;
      }
    }
  }
}
