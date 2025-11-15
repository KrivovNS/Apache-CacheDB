package com.mipt.userstorage.dao;

import com.mipt.userstorage.database.DatabaseConnection;
import com.mipt.userstorage.model.UserStoragePermission;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PermissionDAO {

  public List<UserStoragePermission> findByUserId(Long userId) {
    List<UserStoragePermission> permissions = new ArrayList<>();
    String sql = "SELECT * FROM user_storage_permissions WHERE user_id = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setLong(1, userId);
      ResultSet rs = stmt.executeQuery();

      while (rs.next()) {
        UserStoragePermission permission = new UserStoragePermission();
        permission.setUserId(rs.getLong("user_id"));
        permission.setStorageId(rs.getLong("storage_id"));
        permission.setPermissionType(rs.getString("permission_type"));
        permissions.add(permission);
      }

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return permissions;
  }

  public boolean grantPermission(Long userId, Long storageId, String permissionType) {
    String sql = "INSERT INTO user_storage_permissions (user_id, storage_id, permission_type) VALUES (?, ?, ?)";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setLong(1, userId);
      stmt.setLong(2, storageId);
      stmt.setString(3, permissionType);
      return stmt.executeUpdate() > 0;

    } catch (SQLException e) {
      e.printStackTrace();
      return false;
    }
  }

  public boolean hasPermission(Long userId, Long storageId, String permissionType) {
    String sql = "SELECT COUNT(*) FROM user_storage_permissions WHERE user_id = ? AND storage_id = ? AND permission_type = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setLong(1, userId);
      stmt.setLong(2, storageId);
      stmt.setString(3, permissionType);

      ResultSet rs = stmt.executeQuery();
      return rs.next() && rs.getInt(1) > 0;

    } catch (SQLException e) {
      e.printStackTrace();
      return false;
    }
  }
}
