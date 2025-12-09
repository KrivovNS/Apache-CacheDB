package com.mipt.database.dao;

import com.mipt.model.CacheEntry;
import com.mipt.model.DataType;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.service.CacheStorageService;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class CacheEntryDAO {

  /**
   * Создает таблицу для хранения кэш-записей определенного типа
   */
  public void createTableForDataType(DataType dataType) throws SQLException {
    String tableName = getTableName(dataType);

    String createTableSQL = String.format("""
        CREATE TABLE IF NOT EXISTS %s (
            cache_key VARCHAR(512) PRIMARY KEY,
            data_value %s NOT NULL,
            size_bytes BIGINT NOT NULL,
            created_by_user VARCHAR(255) NOT NULL,
            created_at TIMESTAMP NOT NULL,
            expires_at TIMESTAMP,
            access_count INT DEFAULT 0
        )
        """, tableName, getSqlDataType(dataType));

    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(createTableSQL);
      System.out.println("Таблица создана/уже существует: " + tableName);
    }
  }

  /**
   * Создает все необходимые таблицы для всех типов данных
   */
  public void createAllTables() throws SQLException {
    for (DataType dataType : DataType.values()) {
      createTableForDataType(dataType);
    }
  }

  /**
   * Сохраняет CacheEntry в БД
   */
  public void saveCacheEntry(String key, CacheEntry entry) throws SQLException {
    if (entry == null || key == null || key.trim().isEmpty()) {
      throw new IllegalArgumentException("Key и entry не могут быть null/пустыми");
    }

    String tableName = getTableName(entry.getDataType());

    // Для H2 используем MERGE вместо ON DUPLICATE KEY UPDATE
    String sql = String.format("""
        MERGE INTO %s (cache_key, data_value, size_bytes, 
                      created_by_user, created_at, expires_at, access_count)
        KEY(cache_key)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, tableName);

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {

      pstmt.setString(1, key);

      // Сохраняем данные в зависимости от типа
      setDataValue(pstmt, 2, entry.getData(), entry.getDataType());

      pstmt.setLong(3, entry.getSizeInBytes());
      pstmt.setString(4, entry.getCreatedByUser());
      pstmt.setTimestamp(5, Timestamp.from(entry.getCreatedAt()));

      if (entry.getExpiresAt() != null) {
        pstmt.setTimestamp(6, Timestamp.from(entry.getExpiresAt()));
      } else {
        pstmt.setNull(6, Types.TIMESTAMP);
      }

      pstmt.setInt(7, entry.getAccessCount());

      pstmt.executeUpdate();
    }
  }

  /**
   * Удаляет запись по ключу и типу данных
   */
  public boolean deleteCacheEntry(String key, DataType dataType) throws SQLException {
    if (key == null || key.trim().isEmpty() || dataType == null) {
      throw new IllegalArgumentException("Key и dataType не могут быть null/пустыми");
    }

    String tableName = getTableName(dataType);
    String sql = String.format("DELETE FROM %s WHERE cache_key = ?", tableName);

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {

      pstmt.setString(1, key);
      int rowsAffected = pstmt.executeUpdate();

      return rowsAffected > 0;
    }
  }

  /**
   * Метод для загрузки данных из БД в cacheStorage
   */
  public void loadEntriesIntoCacheStorage(CacheStorageService cacheService) throws SQLException {
    for (DataType dataType : DataType.values()) {
      String tableName = getTableName(dataType);
      String sql = String.format("""
          SELECT cache_key, data_value, size_bytes, 
                 created_by_user, created_at, expires_at, access_count
          FROM %s WHERE expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP
          """, tableName);

      try (Connection conn = DatabaseConnection.getConnection();
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery(sql)) {

        Instant now = Instant.now();

        while (rs.next()) {
          String key = rs.getString("cache_key");
          Object data = extractDataValue(rs, dataType);
          long sizeBytes = rs.getLong("size_bytes");
          String createdByUser = rs.getString("created_by_user");
          Instant createdAt = rs.getTimestamp("created_at").toInstant();

          // Рассчитываем TTL
          Long ttlSeconds = null;
          Timestamp expiresAtTimestamp = rs.getTimestamp("expires_at");
          if (expiresAtTimestamp != null && !rs.wasNull()) {
            Instant expiresAt = expiresAtTimestamp.toInstant();
            ttlSeconds = now.until(expiresAt, ChronoUnit.SECONDS);
            if (ttlSeconds <= 0) continue;
          }

          // Добавляем в кэш
          cacheService.post(key, data, dataType, createdByUser, ttlSeconds, sizeBytes);
        }
      }
    }
  }

  private String getTableName(DataType dataType) {
    return "cache_" + dataType.name().toLowerCase();
  }

  private String getSqlDataType(DataType dataType) {
    return switch (dataType) {
      case JSON, STRING -> "TEXT";
      case BYTES -> "BLOB";
    };
  }

  private void setDataValue(PreparedStatement pstmt, int index, Object data, DataType dataType)
      throws SQLException {
    if (data == null) {
      throw new SQLException("Data не может быть null для CacheEntry");
    }

    switch (dataType) {
      case JSON, STRING -> pstmt.setString(index, data.toString());
      case BYTES -> {
        if (data instanceof byte[]) {
          pstmt.setBytes(index, (byte[]) data);
        } else if (data instanceof String) {
          // Предполагаем, что строка содержит Base64
          pstmt.setBytes(index, Base64.getDecoder().decode((String) data));
        } else {
          throw new SQLException("Неподдерживаемый тип данных для BYTES: " + data.getClass());
        }
      }
    }
  }

  private Object extractDataValue(ResultSet rs, DataType dataType) throws SQLException {
    return switch (dataType) {
      case JSON, STRING -> rs.getString("data_value");
      case BYTES -> rs.getBytes("data_value");
    };
  }
}