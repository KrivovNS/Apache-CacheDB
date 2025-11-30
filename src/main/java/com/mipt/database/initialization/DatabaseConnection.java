package com.mipt.database.initialization;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import com.mipt.util.AppLogger;

public class DatabaseConnection {

  private static final AppLogger log = AppLogger.getLogger(DatabaseConnection.class);

  private static Connection connection;

  public static Connection getConnection() throws SQLException {
    if (connection == null || connection.isClosed()) {
      try {
        // Прямые настройки вместо properties файла
        String url = "jdbc:h2:file:./data/storagedb;DB_CLOSE_DELAY=-1";
        String username = "sa";
        String password = "password";

        log.info("Connecting to database: " + url);

        // Создаем папку data если её нет
        try {
          java.nio.file.Files.createDirectories(java.nio.file.Paths.get("./data"));
        } catch (Exception e) {
          log.warn("Could not create data directory: " + e.getMessage());
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