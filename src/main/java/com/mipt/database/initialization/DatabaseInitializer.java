package com.mipt.database.initialization;

import java.sql.Connection;
import java.sql.Statement;

public class DatabaseInitializer {

  public static void initializeDatabase() {
    String[] createTables = {
        "CREATE TABLE IF NOT EXISTS users (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "username VARCHAR(50) UNIQUE NOT NULL, " +
            "password VARCHAR(255) NOT NULL, " +
            "storage_token VARCHAR(255) UNIQUE NOT NULL)"
    };

    String[] insertData = {
        "DELETE FROM users",
        "ALTER TABLE users ALTER COLUMN id RESTART WITH 1"
    };

    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement()) {

      // Создаем таблицы
      for (String sql : createTables) {
        stmt.execute(sql);
      }

      for (String sql : insertData) {
        stmt.execute(sql);
      }

      System.out.println("Database initialized successfully!");

    } catch (Exception e) {
      System.err.println("Error initializing database: " + e.getMessage());
      e.printStackTrace();
    }
  }
}