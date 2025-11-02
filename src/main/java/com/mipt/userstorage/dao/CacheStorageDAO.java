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

  /**
   * Сохранение хранилища (создание или обновление)
   */
  public boolean save(CacheStorageEntity entity) {
    if (entity.getId() == null) {
      return insert(entity);
    } else {
      return update(entity);
    }
  }

  /**
   * Создание нового хранилища
   */
  public boolean insert(CacheStorageEntity entity) {
    String sql = "INSERT INTO cache_storages (storage_name, cache_type, max_size) VALUES (?, ?, ?)";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

      stmt.setString(1, entity.getStorageName());
      stmt.setString(2, entity.getCacheType());

      if (entity.getMaxSize() != null) {
        stmt.setInt(3, entity.getMaxSize());
      } else {
        stmt.setNull(3, Types.INTEGER);
      }

      int affectedRows = stmt.executeUpdate();

      if (affectedRows > 0) {
        // Получаем сгенерированный ID
        try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
          if (generatedKeys.next()) {
            entity.setId(generatedKeys.getLong(1));
          }
        }
        return true;
      }

    } catch (SQLException e) {
      System.err.println("Error inserting cache storage: " + e.getMessage());
      e.printStackTrace();
    }

    return false;
  }

  /**
   * Обновление существующего хранилища
   */
  public boolean update(CacheStorageEntity entity) {
    String sql = "UPDATE cache_storages SET storage_name = ?, cache_type = ?, max_size = ? WHERE id = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, entity.getStorageName());
      stmt.setString(2, entity.getCacheType());

      if (entity.getMaxSize() != null) {
        stmt.setInt(3, entity.getMaxSize());
      } else {
        stmt.setNull(3, Types.INTEGER);
      }

      stmt.setLong(4, entity.getId());

      return stmt.executeUpdate() > 0;

    } catch (SQLException e) {
      System.err.println("Error updating cache storage: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }

  /**
   * Удаление хранилища по имени
   */
  public boolean deleteByName(String storageName) {
    String sql = "DELETE FROM cache_storages WHERE storage_name = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, storageName);
      int affectedRows = stmt.executeUpdate();
      return affectedRows > 0;

    } catch (SQLException e) {
      System.err.println("Error deleting cache storage by name: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }

  /**
   * Проверка существования хранилища по имени
   */
  public boolean existsByName(String storageName) {
    String sql = "SELECT COUNT(*) FROM cache_storages WHERE storage_name = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, storageName);
      ResultSet rs = stmt.executeQuery();

      return rs.next() && rs.getInt(1) > 0;

    } catch (SQLException e) {
      System.err.println("Error checking cache storage existence: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }


  /**
   * Поиск хранилищ по типу
   */
  public List<CacheStorageEntity> findByType(String cacheType) {
    List<CacheStorageEntity> storages = new ArrayList<>();
    String sql = "SELECT * FROM cache_storages WHERE cache_type = ? ORDER BY storage_name";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, cacheType);
      ResultSet rs = stmt.executeQuery();

      while (rs.next()) {
        CacheStorageEntity storage = new CacheStorageEntity();
        storage.setId(rs.getLong("id"));
        storage.setStorageName(rs.getString("storage_name"));
        storage.setCacheType(rs.getString("cache_type"));
        storage.setMaxSize(rs.getInt("max_size"));
        storages.add(storage);
      }

    } catch (SQLException e) {
      System.err.println("Error finding cache storages by type: " + e.getMessage());
      e.printStackTrace();
    }

    return storages;
  }
}