package com.mipt.database.dao;

import com.mipt.controller.MemoryCalculator;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.model.CacheEntry;
import com.mipt.model.CacheStorage;
import com.mipt.model.DataType;
import com.mipt.service.CacheStorageService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CacheEntryDAO {

  private static final Logger log = LoggerFactory.getLogger(CacheEntryDAO.class);
  private static final int MAX_RETRY_ATTEMPTS = 3;
  private static final long RETRY_BACKOFF_MS = 25L;

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

  public void createAllTables() throws SQLException {
    for (DataType dataType : DataType.values()) {
      createTableForDataType(dataType);
    }
  }

  public void saveCacheEntry(String key, CacheEntry entry) throws SQLException {
    if (entry == null || key == null || key.trim().isEmpty()) {
      throw new IllegalArgumentException("Key и entry не могут быть null/пустыми");
    }

    String tableName = getTableName(entry.getDataType());
    String sql = String.format("""
        MERGE INTO %s (cache_key, data_value, size_bytes,
                      created_by_user, created_at, expires_at)
        KEY(cache_key)
        VALUES (?, ?, ?, ?, ?, ?)
        """, tableName);

    executeWithRetry(() -> {
      try (Connection conn = DatabaseConnection.getConnection();
          PreparedStatement pstmt = conn.prepareStatement(sql)) {

        pstmt.setString(1, key);
        setDataValue(pstmt, 2, entry.getData(), entry.getDataType());
        pstmt.setLong(3, entry.getSizeInBytes());
        pstmt.setString(4, entry.getCreatedByUser());
        pstmt.setTimestamp(5, Timestamp.from(entry.getCreatedAt()));

        if (entry.getExpiresAt() != null) {
          pstmt.setTimestamp(6, Timestamp.from(entry.getExpiresAt()));
        } else {
          pstmt.setNull(6, Types.TIMESTAMP);
        }

        pstmt.executeUpdate();
      }
    }, "saveCacheEntry");
  }

  public void deleteCacheEntry(String key, DataType dataType) throws SQLException {
    if (key == null || key.trim().isEmpty() || dataType == null) {
      throw new IllegalArgumentException("Key и dataType не могут быть null/пустыми");
    }

    String tableName = getTableName(dataType);
    String sql = String.format("DELETE FROM %s WHERE cache_key = ?", tableName);

    executeWithRetry(() -> {
      try (Connection conn = DatabaseConnection.getConnection();
          PreparedStatement pstmt = conn.prepareStatement(sql)) {
        pstmt.setString(1, key);
        pstmt.executeUpdate();
      }
    }, "deleteCacheEntry");
  }

  public void loadEntriesIntoCacheStorage(CacheStorageService cacheService) throws SQLException {
    try {
      var databaseField = CacheStorageService.class.getDeclaredField("database");
      databaseField.setAccessible(true);
      CacheStorage database = (CacheStorage) databaseField.get(cacheService);

      for (DataType dataType : DataType.values()) {
        String tableName = getTableName(dataType);
        String sql = String.format("""
                SELECT cache_key, data_value, size_bytes,
                           created_by_user, created_at, expires_at
                FROM %s WHERE expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP
                """, tableName);

        try (Connection conn = DatabaseConnection.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {

          while (rs.next()) {
            String key = rs.getString("cache_key");
            Object data = extractDataValue(rs, dataType);
            long sizeBytes = MemoryCalculator.calculateEntrySizeBytes(key, dataType, data);
            String createdByUser = rs.getString("created_by_user");
            Instant createdAt = rs.getTimestamp("created_at").toInstant();

            Long ttlSeconds = null;
            Timestamp expiresAtTimestamp = rs.getTimestamp("expires_at");
            if (expiresAtTimestamp != null && !rs.wasNull()) {
              Instant expiresAt = expiresAtTimestamp.toInstant();
              ttlSeconds = ChronoUnit.SECONDS.between(createdAt, expiresAt);
            }

            CacheEntry entry = new CacheEntry(dataType, data, sizeBytes, createdByUser, ttlSeconds);
            database.restoreEntry(key, entry);
          }
        }
      }
    } catch (Exception e) {
      throw new SQLException("Ошибка загрузки данных в кэш: " + e.getMessage(), e);
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

  private void executeWithRetry(SqlOperation operation, String operationName) throws SQLException {
    SQLException lastException = null;

    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
      try {
        operation.run();
        return;
      } catch (SQLException e) {
        lastException = e;
        if (!isTransientSqlError(e) || attempt == MAX_RETRY_ATTEMPTS) {
          throw e;
        }

        log.warn("Transient SQL error during {} (attempt {}/{}): {}",
            operationName, attempt, MAX_RETRY_ATTEMPTS, e.getMessage());

        try {
          Thread.sleep(RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          throw new SQLException("Interrupted during retry backoff", interruptedException);
        }
      }
    }

    throw lastException;
  }

  private boolean isTransientSqlError(SQLException e) {
    String sqlState = e.getSQLState();
    if (sqlState != null && (sqlState.startsWith("40") || sqlState.startsWith("08"))) {
      return true;
    }

    String message = e.getMessage();
    if (message == null) {
      return false;
    }

    String normalized = message.toLowerCase();
    return normalized.contains("lock")
        || normalized.contains("deadlock")
        || normalized.contains("timeout")
        || normalized.contains("concurrent")
        || normalized.contains("closed");
  }

  @FunctionalInterface
  private interface SqlOperation {
    void run() throws SQLException;
  }
}
