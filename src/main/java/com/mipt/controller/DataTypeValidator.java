package com.mipt.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

public class DataTypeValidator {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  public static boolean validateDataByType(String data, String type) {
    if (data == null || data.trim().isEmpty()) {
      return false;
    }

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return false;
    }

    return switch (dataType) {
      case JSON -> isValidJSON(data);
      case BYTES -> isValidBase64(data);
      case STRING -> true;
    };
  }

  /**
   * Проверяет валидность JSON с помощью ObjectMapper
   */
  public static boolean isValidJSON(String data) {
    try {
      objectMapper.readTree(data);
      return true;
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  public static boolean isValidBase64(String data) {
    try {
      java.util.Base64.getDecoder().decode(data);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Преобразует данные для хранения в соответствующий тип
   */
  public static Object processDataForStorage(String data, String type) {
    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      throw new IllegalArgumentException("Unsupported data type: " + type);
    }

    switch (dataType) {
      case BYTES:
        try {
          return java.util.Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Invalid Base64 data: " + e.getMessage());
        }
      case JSON:
        try {
          objectMapper.readTree(data);
          return data;
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("Invalid JSON format: " + e.getMessage());
        }
      case STRING:
        return data;
      default:
        throw new IllegalArgumentException("Unsupported data type: " + type);
    }
  }

  /**
   * Форматирует данные для ответа
   */
  public static String formatDataForResponse(Object data, String type) {
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
            return java.util.Base64.getEncoder().encodeToString((byte[]) data);
          } else if (data instanceof String) {
            String stringData = (String) data;
            if (isValidBase64(stringData)) {
              return stringData;
            }
          }
          return data.toString();

        case JSON:
          if (data instanceof String) {
            String jsonData = (String) data;
            objectMapper.readTree(jsonData);
            return jsonData;
          }
          return data.toString();

        case STRING:
        default:
          return data.toString();
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Error formatting data for response: " + e.getMessage());
    }
  }
}