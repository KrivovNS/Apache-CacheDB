package com.mipt.database.initialization;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseConnection {

  private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);
  private static final String DEFAULT_DB_URL = "jdbc:h2:file:./data/storagedb;DB_CLOSE_DELAY=-1";
  private static final String DEFAULT_DB_USERNAME = "sa";
  private static final String DEFAULT_DB_PASSWORD = "";
  private static Connection connection;

  public static Connection getConnection() throws SQLException {
    if (connection == null || connection.isClosed()) {
      try {
        Properties props = new Properties();

        try (InputStream input = DatabaseConnection.class
            .getClassLoader()
            .getResourceAsStream("application.properties")) {

          if (input != null) {
            props.load(input);
          }
        }

        // System properties override file config (useful for CI/tests).
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

        log.info("Connecting to database: {}", url);

        // Создаем папку data если её нет
        try {
          java.nio.file.Files.createDirectories(java.nio.file.Paths.get("./data"));
        } catch (Exception e) {
          log.warn("Could not create data directory: {}", e.getMessage());
        }

        connection = DriverManager.getConnection(url, username, password);
        log.info("Database connected successfully!");

      } catch (Exception e) {
        log.error("Connection failed", e);
        throw new SQLException("Failed to connect to database", e);
      }
    }
    return connection;
  }

  public static void closeConnection() {
    if (connection != null) {
      try {
        connection.close();
        log.info("Database connection closed");
      } catch (SQLException e) {
        log.error("Error closing database connection", e);
      }
    }
  }
}
