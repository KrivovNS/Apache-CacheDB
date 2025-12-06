package com.mipt.database.dao;

import com.mipt.model.PermissionType;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.model.User;
import java.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserDAO {

  private static final Logger log = LoggerFactory.getLogger(UserDAO.class);

  public User findByUsername(String username) {
    String sql = "SELECT id, username, password, permission FROM users WHERE username = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, username);
      ResultSet rs = stmt.executeQuery();

      if (rs.next()) {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPassword(rs.getString("password"));
        user.setPermissionType(PermissionType.fromString(rs.getString("permission")));
        return user;
      }

    } catch (SQLException e) {
      log.error("Error finding user by username: {}", username, e);
    }

    return null;
  }

  public User createUser(String username, String password, PermissionType permissionType) {
    String sql = "INSERT INTO users (username, password, permission) VALUES (?, ?, ?)";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

      stmt.setString(1, username);
      stmt.setString(2, password);
      stmt.setString(3, permissionType.getValue());

      int affectedRows = stmt.executeUpdate();
      if (affectedRows == 0) {
        throw new SQLException("Creating user failed, no rows affected.");
      }

      // Получаем сгенерированный ID
      try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
        if (generatedKeys.next()) {
          Long id = generatedKeys.getLong(1);
          return new User(id, username, password, permissionType);
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
   * Удаляет пользователя по логину
   */
  public boolean deleteUser(String username) {
    String sql = "DELETE FROM users WHERE username = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, username);
      int affectedRows = stmt.executeUpdate();
      return affectedRows > 0;

    } catch (SQLException e) {
      log.error("Error deleting user: {}", username, e);
      return false;
    }
  }

  /**
   * Обновляет данные пользователя
   */
  public boolean updateUser(String username, String newUsername, String newPassword, PermissionType newPermission) {
    StringBuilder sql = new StringBuilder("UPDATE users SET ");
    boolean hasUpdate = false;

    if (newUsername != null && !newUsername.equals(username)) {
      sql.append("username = ?");
      hasUpdate = true;
    }

    if (newPassword != null && !newPassword.isEmpty()) {
      if (hasUpdate) sql.append(", ");
      sql.append("password = ?");
      hasUpdate = true;
    }

    if (newPermission != null) {
      if (hasUpdate) sql.append(", ");
      sql.append("permission = ?");
      hasUpdate = true;
    }

    if (!hasUpdate) {
      return false; // Нет изменений
    }

    sql.append(" WHERE username = ?");

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

      int paramIndex = 1;

      if (newUsername != null && !newUsername.equals(username)) {
        stmt.setString(paramIndex++, newUsername);
      }

      if (newPassword != null && !newPassword.isEmpty()) {
        stmt.setString(paramIndex++, newPassword);
      }

      if (newPermission != null) {
        stmt.setString(paramIndex++, newPermission.getValue());
      }

      stmt.setString(paramIndex, username);

      int affectedRows = stmt.executeUpdate();
      return affectedRows > 0;

    } catch (SQLException e) {
      log.error("Error updating user: {}", username, e);
      return false;
    }
  }
}