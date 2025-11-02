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
            "storage_name VARCHAR(100) NOT NULL, " +
            "cache_type VARCHAR(50) NOT NULL, " +
            "max_size INT)",

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
        "INSERT INTO users (username, password_plain) VALUES ('admin', 'password')",

        // Добавляем хранилище test_cache
        "INSERT INTO cache_storages (storage_name, cache_type, max_size) VALUES ('test_cache', 'LRU', 1000)",

        // Добавляем права admin на test_cache
        "INSERT INTO user_storage_permissions (user_id, storage_id, permission_type) " +
            "SELECT u.id, cs.id, 'ADMIN' FROM users u, cache_storages cs " +
            "WHERE u.username = 'admin' AND cs.storage_name = 'test_cache'"
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