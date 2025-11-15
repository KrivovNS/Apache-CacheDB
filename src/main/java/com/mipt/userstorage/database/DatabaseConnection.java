package com.mipt.userstorage.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {
  private static Connection connection;

  public static Connection getConnection() throws SQLException {
    if (connection == null || connection.isClosed()) {
      try {
        // Прямые настройки вместо properties файла
        String url = "jdbc:h2:file:./data/storagedb;DB_CLOSE_DELAY=-1";
        String username = "sa";
        String password = "password";

        System.out.println("Connecting to database: " + url);

        // Создаем папку data если её нет
        try {
          java.nio.file.Files.createDirectories(java.nio.file.Paths.get("./data"));
        } catch (Exception e) {
          System.err.println("Warning: Could not create data directory: " + e.getMessage());
        }

        connection = DriverManager.getConnection(url, username, password);
        System.out.println("Database connected successfully!");

      } catch (Exception e) {
        System.err.println("Connection failed: " + e.getMessage());
        throw new SQLException("Failed to connect to database", e);
      }
    }
    return connection;
  }

  public static void closeConnection() {
    if (connection != null) {
      try {
        connection.close();
        System.out.println("Database connection closed");
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }
  }
}
