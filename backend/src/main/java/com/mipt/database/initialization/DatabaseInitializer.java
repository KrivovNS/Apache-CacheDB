package com.mipt.database.initialization;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseInitializer {

  private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

  public static void initializeDatabase() {
    String[] createTables = {
        """
            CREATE TABLE IF NOT EXISTS users (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(50) UNIQUE NOT NULL,
            password VARCHAR(255) NOT NULL,
            permission VARCHAR(255) NOT NULL)"""
    };

    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement()) {

      // Создаем таблицы
      for (String sql : createTables) {
        stmt.execute(sql);
      }

      log.info("Database tables created successfully!");

      // Добавляем пользователя по умолчанию, если его нет
      addDefaultUser(conn);

    } catch (Exception e) {
      log.error("Error initializing database", e);
    }
  }

  private static void addDefaultUser(Connection conn) {
    String checkSql = "SELECT COUNT(*) FROM users WHERE username = 'default'";
    String insertSql = "INSERT INTO users (username, password, permission) VALUES ('default', 'admin123', 'superadmin')";

    try (Statement stmt = conn.createStatement()) {
      // Проверяем, существует ли уже пользователь default
      var rs = stmt.executeQuery(checkSql);
      if (rs.next() && rs.getInt(1) == 0) {
        // Пользователя нет - создаем
        int rowsInserted = stmt.executeUpdate(insertSql);
        if (rowsInserted > 0) {
          log.info("Default user created: username=default, password=admin123, permission=superadmin");
        } else {
          log.error("Failed to create default user");
        }
      } else {
        log.info("Default user already exists in database");
      }
    } catch (Exception e) {
      log.error("Error adding default user", e);
    }
  }
}