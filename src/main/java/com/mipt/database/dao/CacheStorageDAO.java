package com.mipt.database.dao;

import com.mipt.cache.Cache;
import com.mipt.controller.DataType;
import com.mipt.model.CacheStorage;
import com.mipt.database.initialization.DatabaseConnection;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CacheStorageDAO {
  /**
   * Метод для сохранения данных из кэша в БД
   */
  public void saveDataFromCacheInDatabase(CacheStorage cacheStorage) {
    if (cacheStorage == null) {
      throw new IllegalArgumentException("CacheStorage не может быть null");
    }

    String storageToken = cacheStorage.getStorageToken();
    if (storageToken == null || storageToken.trim().isEmpty()) {
      throw new IllegalStateException("StorageToken не может быть null или пустым");
    }

    try (Connection conn = DatabaseConnection.getConnection()) {
      if (conn.isClosed()) {
        throw new SQLException("Соединение с БД закрыто");
      }

      conn.setAutoCommit(false);

      try {
        for (DataType type : cacheStorage.getCacheTypes()) {
          String tableName = generateTableName(storageToken, type);
          Cache cacheByData = cacheStorage.getCacheByType(type);

          // Пропускаем если кеш пустой
          if (cacheByData == null || cacheByData.size() == 0) {
            System.out.println("Пропуск пустого кеша для типа: " + type);
            continue;
          }

          // 1. Создаем таблицу
          createTableForType(conn, storageToken, type);

          // 2. Очищаем таблицу
          clearTable(conn, tableName);

          // 3. Вставляем данные из кеша
          int insertedRows = insertCacheData(conn, tableName, cacheByData, type);

          System.out.println("Создана таблица и перенесено " + insertedRows +
              " записей для типа: " + type + " в таблицу: " + tableName);
        }

        conn.commit();
        System.out.println("Все данные успешно перенесены в БД");

      } catch (SQLException e) {
        conn.rollback();
        throw e;
      }

    } catch (SQLException e) {
      System.err.println("Ошибка при сохранении данных в БД: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Метод для загрузки данных из БД в CacheStorage по токену хранилища
   */
  public CacheStorage loadDataFromDatabaseByToken(String storageToken) {
    if (storageToken == null || storageToken.trim().isEmpty()) {
      throw new IllegalArgumentException("StorageToken не может быть null или пустым");
    }

    try (Connection conn = DatabaseConnection.getConnection()) {
      // Создаем объект хранилища
      CacheStorage storage = new CacheStorage();
      storage.setStorageToken(storageToken);

      // Получаем все таблицы для данного storageToken
      List<String> tableNames = getStorageTables(conn, storageToken);

      for (String tableName : tableNames) {
        // Извлекаем DataType из имени таблицы
        DataType dataType = extractDataTypeFromTableName(storageToken, tableName);
        if (dataType != null) {
          loadCacheDataFromTable(conn, tableName, dataType, storage);
        } else {
          System.out.println("Не удалось определить тип данных для таблицы: " + tableName);
        }
      }

      System.out.println("Данные успешно загружены из БД в CacheStorage");
      return storage;

    } catch (SQLException e) {
      System.err.println("Ошибка при загрузке данных из БД: " + e.getMessage());
      e.printStackTrace();
      return null;
    }
  }

  private void createTableForType(Connection conn, String storageToken, DataType type) throws SQLException {
    String tableName = generateTableName(storageToken, type);
    String createTableSQL = String.format(
        "CREATE TABLE IF NOT EXISTS %s (" +
            "\"key\" VARCHAR(255) PRIMARY KEY, " +  // Экранируем key кавычками
            "\"value\" %s NOT NULL" +               // Экранируем value тоже для consistency
            ")", tableName, getSqlTypeForDataType(type));

    try (Statement stmt = conn.createStatement()) {
      stmt.execute(createTableSQL);
      System.out.println("Таблица создана: " + tableName + " с типом данных: " + type.getValue());
    }
  }

  private String generateTableName(String storageToken, DataType type) {
    return storageToken + "_" + type.getValue();
  }

  private String getSqlTypeForDataType(DataType dataType) {
    if (dataType == null) {
      return "CLOB";
    }

    return switch (dataType) {
      case JSON, STRING -> "CLOB";
      case BYTES -> "BLOB";
    };
  }

  private void clearTable(Connection conn, String tableName) throws SQLException {
    String clearSQL = "DELETE FROM " + tableName;
    try (PreparedStatement stmt = conn.prepareStatement(clearSQL)) {
      int deletedRows = stmt.executeUpdate();
      System.out.println("Очищено " + deletedRows + " записей из таблицы: " + tableName);
    }
  }

  private int insertCacheData(Connection conn, String tableName, Cache cache, DataType dataType) throws SQLException {
    String insertSQL = "INSERT INTO " + tableName + " (\"key\", \"value\") VALUES (?, ?)";
    int insertedRows = 0;

    try (PreparedStatement stmt = conn.prepareStatement(insertSQL)) {
      for (Object keyObj : cache.getKeys()) {
        String key = keyObj.toString();
        Object value = cache.get(key);

        if (value == null) {
          continue;
        }

        stmt.setString(1, key);

        switch (dataType) {
          case JSON, STRING -> stmt.setString(2, value.toString());
          case BYTES -> {
            if (value instanceof byte[]) {
              stmt.setBytes(2, (byte[]) value);
            } else {
              stmt.setBytes(2, value.toString().getBytes());
            }
          }
          default -> stmt.setString(2, value.toString());
        }

        stmt.addBatch();
        insertedRows++;
      }

      if (insertedRows > 0) {
        stmt.executeBatch();
      }
    }

    return insertedRows;
  }

  private List<String> getStorageTables(Connection conn, String storageToken) throws SQLException {
    List<String> tables = new ArrayList<>();
    String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
        "WHERE TABLE_NAME LIKE ? AND TABLE_SCHEMA = 'PUBLIC'";

    // УБРАТЬ создание нового соединения! Используем переданное
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, storageToken.toUpperCase() + "_%");

      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          tables.add(rs.getString("TABLE_NAME"));
        }
      }
    }

    return tables;
  }

  private DataType extractDataTypeFromTableName(String storageToken, String tableName) {
    try {
      String typePart = tableName.substring(storageToken.length() + 1);
      return DataType.fromValue(typePart.toLowerCase());
    } catch (Exception e) {
      System.err.println("Ошибка при разборе имени таблицы: " + tableName);
      return null;
    }
  }

  private void loadCacheDataFromTable(Connection conn, String tableName, DataType dataType, CacheStorage cacheStorage)
      throws SQLException {

    String selectSQL = "SELECT \"key\", \"value\" FROM " + tableName;
    int loadedCount = 0;

    PreparedStatement stmt = null;
    ResultSet rs = null;

    try {
      stmt = conn.prepareStatement(selectSQL);
      rs = stmt.executeQuery();

      while (rs.next()) {
        String key = rs.getString("key");
        Object value = extractValueFromResultSet(rs, dataType);

        if (key != null && value != null) {
          cacheStorage.post(dataType, key, value);
          loadedCount++;
        }
      }

      System.out.println("Загружено " + loadedCount + " записей из таблицы: " + tableName + " для типа: " + dataType);

    } finally {
      if (rs != null) {
        try { rs.close(); } catch (SQLException e) {}
      }
      if (stmt != null) {
        try { stmt.close(); } catch (SQLException e) {}
      }
    }
  }

  private Object extractValueFromResultSet(ResultSet rs, DataType dataType) throws SQLException {
    return switch (dataType) {
      case JSON, STRING -> rs.getString("value");
      case BYTES -> rs.getBytes("value");
    };
  }
}