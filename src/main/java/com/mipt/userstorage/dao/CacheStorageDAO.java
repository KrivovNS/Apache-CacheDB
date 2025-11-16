package com.mipt.userstorage.dao;

import com.mipt.userstorage.database.DatabaseConnection;
import com.mipt.userstorage.model.CacheStorageEntity;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CacheStorageDAO {

  public List<CacheStorageEntity> findAll() {
    List<CacheStorageEntity> storages = new ArrayList<>();
    String sql = "SELECT * FROM cache_storages";

    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {

      while (rs.next()) {
        CacheStorageEntity storage = new CacheStorageEntity();
        storage.setId(rs.getLong("id"));
        storage.setStorageToken(rs.getString("storage_token"));
        storages.add(storage);
      }

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return storages;
  }

  public CacheStorageEntity findByName(String storageName) {
    String sql = "SELECT * FROM cache_storages WHERE storage_name = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, storageName);
      ResultSet rs = stmt.executeQuery();

      if (rs.next()) {
        CacheStorageEntity storage = new CacheStorageEntity();
        storage.setId(rs.getLong("id"));
        storage.setStorageToken(rs.getString("storage_token"));
        return storage;
      }

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return null;
  }

  public Long createStorage(String storageToken) {
    String sql = "INSERT INTO cache_storages (storage_token) VALUES (?)";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

      stmt.setString(1, storageToken);

      int affectedRows = stmt.executeUpdate();
      if (affectedRows == 0) {
        throw new SQLException("Creating storage failed, no rows affected.");
      }

      // Получаем сгенерированный ID
      try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
        if (generatedKeys.next()) {
          return generatedKeys.getLong(1);
        } else {
          throw new SQLException("Creating storage failed, no ID obtained.");
        }
      }

    } catch (SQLException e) {
      System.err.println("Error creating storage: " + e.getMessage());
      e.printStackTrace();
      return null;
    }
  }
}