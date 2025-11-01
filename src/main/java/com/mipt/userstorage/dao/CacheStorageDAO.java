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
        storage.setStorageName(rs.getString("storage_name"));
        storage.setCacheType(rs.getString("cache_type"));
        storage.setMaxSize(rs.getInt("max_size"));
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
        storage.setStorageName(rs.getString("storage_name"));
        storage.setCacheType(rs.getString("cache_type"));
        storage.setMaxSize(rs.getInt("max_size"));
        return storage;
      }

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return null;
  }
}