package com.mipt.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;

public class DataTypeProcessor {

  private final ObjectMapper objectMapper;

  public DataTypeProcessor() {
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Валидация данных в зависимости от типа
   */
  public boolean validateDataByType(String data, String type) {
    if (data == null || data.trim().isEmpty()) {
      return false;
    }

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return false;
    }

    try {
      switch (dataType) {
        case JSON:
          return isValidJSON(data);
        case BYTES:
          return isValidBase64(data);
        case STRING:
          return true;
        default:
          return false;
      }
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Обработка данных для сохранения в кэш
   */
  public Object processDataForStorage(String data, String type) {
    if (data == null) {
      return null;
    }

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return data;
    }

    try {
      switch (dataType) {
        case BYTES:
          return Base64.getDecoder().decode(data);
        case JSON:
          try {
            return objectMapper.readValue(data, Object.class);
          } catch (JsonProcessingException e) {
            return data;
          }
        case STRING:
        default:
          return data;
      }
    } catch (Exception e) {
      return data;
    }
  }

  /**
   * Форматирование данных для ответа
   */
  public String formatDataForResponse(Object data, String type) {
    if (data == null) {
      return "";
    }

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return data.toString();
    }

    try {
      switch (dataType) {
        case BYTES:
          if (data instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) data);
          }
          return data.toString();
        case JSON:
          if (!(data instanceof String)) {
            return objectMapper.writeValueAsString(data);
          }
          String jsonString = (String) data;
          if (isValidJSON(jsonString)) {
            return jsonString;
          } else {
            return jsonString;
          }
        case STRING:
        default:
          return data.toString();
      }
    } catch (Exception e) {
      return data.toString();
    }
  }

  /**
   * Проверка валидности JSON
   */
  private boolean isValidJSON(String data) {
    if (data == null || data.trim().isEmpty()) {
      return false;
    }

    String trimmed = data.trim();
    if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) &&
        !(trimmed.startsWith("[") && trimmed.endsWith("]"))) {
      return false;
    }

    try {
      objectMapper.readTree(data);
      return true;
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  /**
   * Проверка валидности Base64
   */
  private boolean isValidBase64(String data) {
    if (data == null || data.isEmpty()) {
      return false;
    }

    try {
      String cleanData = data.replaceAll("\\s", "");
      Base64.getDecoder().decode(cleanData);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}