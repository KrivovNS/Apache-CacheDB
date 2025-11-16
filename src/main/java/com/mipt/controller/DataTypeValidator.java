package com.mipt.controller;

public class DataTypeValidator {

  public static boolean validateDataByType(String data, String type) {
    if (data == null || data.trim().isEmpty()) {
      return false;
    }

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return false;
    }

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
  }

  public static boolean isValidJSON(String data) {
    try {
      String trimmed = data.trim();
      return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
          (trimmed.startsWith("[") && trimmed.endsWith("]"));
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean isValidBase64(String data) {
    try {
      // Проверка Base64
      java.util.Base64.getDecoder().decode(data);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

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
        if (!isValidJSON(data)) {
          throw new IllegalArgumentException("Invalid JSON format");
        }
        return data;
      case STRING:
        return data;
      default:
        throw new IllegalArgumentException("Unsupported data type: " + type);
    }
  }

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
        case STRING:
        default:
          return data.toString();
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Error formatting data for response: " + e.getMessage());
    }
  }
}