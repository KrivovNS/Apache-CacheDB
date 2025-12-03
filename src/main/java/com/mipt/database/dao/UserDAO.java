package com.mipt.database.dao;

import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.model.User;
import java.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserDAO {

  private static final Logger log = LoggerFactory.getLogger(UserDAO.class);

  public User findByUsername(String username) {
    String sql = "SELECT id, username, password, storage_token FROM users WHERE username = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, username);
      ResultSet rs = stmt.executeQuery();

      if (rs.next()) {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPassword(rs.getString("password"));
        user.setStorageToken(rs.getString("storage_token"));
        return user;
      }

    } catch (SQLException e) {
      log.error("Error finding user by username: {}", username, e);
    }

    return null;
  }

  public User createUser(String username, String password, String storageToken) {
    String sql = "INSERT INTO users (username, password, storage_token) VALUES (?, ?, ?)";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

      stmt.setString(1, username);
      stmt.setString(2, password);
      stmt.setString(3, storageToken);

      int affectedRows = stmt.executeUpdate();
      if (affectedRows == 0) {
        throw new SQLException("Creating user failed, no rows affected.");
      }

      // Получаем сгенерированный ID
      try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
        if (generatedKeys.next()) {
          Long id = generatedKeys.getLong(1);
          User user = new User();
          user.setId(id);
          user.setUsername(username);
          user.setPassword(password);
          user.setStorageToken(storageToken);
          return user;
        } else {
          throw new SQLException("Creating user failed, no ID obtained.");
        }
      }

    } catch (SQLException e) {
      log.error("Error creating user with username: {}", username, e);
      return null;
    }
  }

  /**
   * Проверяет, существует ли пользователь с таким storage_token
   */
  public boolean isStorageTokenExists(String storageToken) {
    String sql = "SELECT COUNT(*) FROM users WHERE storage_token = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, storageToken);
      ResultSet rs = stmt.executeQuery();

      if (rs.next()) {
        return rs.getInt(1) > 0;
      }

    } catch (SQLException e) {
      log.error("Error checking storage token", e);
    }

    return false;
  }

  /**
   * Получает пользователя по storage_token
   */
  public User findByStorageToken(String storageToken) {
    String sql = "SELECT id, username, password, storage_token FROM users WHERE storage_token = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, storageToken);
      ResultSet rs = stmt.executeQuery();

      if (rs.next()) {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPassword(rs.getString("password"));
        user.setStorageToken(rs.getString("storage_token"));
        return user;
      }

    } catch (SQLException e) {
      log.error("Error finding user by storage token", e);
    }

    return null;
  }
}
