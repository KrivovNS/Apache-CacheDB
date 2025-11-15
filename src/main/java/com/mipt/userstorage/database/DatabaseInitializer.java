package com.mipt.userstorage.database;

import java.sql.Connection;
import java.sql.Statement;

public class DatabaseInitializer {

  public static void initializeDatabase() {
    String[] createTables = {
        "CREATE TABLE IF NOT EXISTS users (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "username VARCHAR(50) UNIQUE NOT NULL, " +
            "password_plain VARCHAR(255) NOT NULL)",

        "CREATE TABLE IF NOT EXISTS cache_storages (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
            "storage_token VARCHAR(255) NOT NULL, ",

        "CREATE TABLE IF NOT EXISTS user_storage_permissions (" +
            "user_id BIGINT NOT NULL, " +
            "storage_id BIGINT NOT NULL, " +
            "permission_type VARCHAR(50) NOT NULL, " +
            "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, " +
            "FOREIGN KEY (storage_id) REFERENCES cache_storages(id) ON DELETE CASCADE, " +
            "PRIMARY KEY (user_id, storage_id, permission_type))"
    };

    String[] insertData = {
        // Очищаем и добавляем пользователя admin
        "DELETE FROM user_storage_permissions",
        "DELETE FROM cache_storages",
        "DELETE FROM users",

        "ALTER TABLE users ALTER COLUMN id RESTART WITH 1",
        "ALTER TABLE cache_storages ALTER COLUMN id RESTART WITH 1",
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