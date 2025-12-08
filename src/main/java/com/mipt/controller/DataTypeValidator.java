package com.mipt.controller;


import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.mipt.model.DataType;
import java.io.IOException;

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

    try {
      return switch (dataType) {
        case JSON -> isValidJSON(data);
        case BYTES -> isValidByteArray(data);
        case STRING -> true;
      };
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Проверяет валидность JSON с помощью ObjectMapper Строгая проверка - не должно быть лишних
   * символов
   */
  public static boolean isValidJSON(String data) {
    try {
      validateJsonStrict(data);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Строгая валидация JSON (бросает исключения)
   */
  private static void validateJsonStrict(String data) {
    if (data == null || data.trim().isEmpty()) {
      throw new IllegalArgumentException("JSON data cannot be null or empty");
    }

    String trimmed = data.trim();

    try {
      JsonFactory factory = objectMapper.getFactory();
      JsonParser parser = factory.createParser(trimmed);

      JsonNode node = objectMapper.readTree(parser);

      // Получаем позицию после парсинга
      JsonLocation location = parser.getCurrentLocation();
      int charsConsumed = (int) location.getCharOffset();

      // Проверяем, не осталось ли не-пробельных символов
      if (charsConsumed < trimmed.length()) {
        String remaining = trimmed.substring(charsConsumed);

        if (!remaining.trim().isEmpty()) {
          String nonWhitespace = remaining.replaceAll("\\s+", "");
          if (!nonWhitespace.isEmpty()) {
            throw new IllegalArgumentException("JSON contains extra characters: '" +
                nonWhitespace.substring(0, Math.min(10, nonWhitespace.length())) + "'");
          }
        }
      }

      parser.close();

    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON format: " + e.getMessage());
    } catch (IOException e) {
      throw new IllegalArgumentException("JSON parsing error: " + e.getMessage());
    }
  }


  /**
   * Проверяет, что строка является валидным представлением массива байтов.
   * Принимает либо Base64, либо hex строку
   */
  public static boolean isValidByteArray(String data) {
    if (data == null || data.trim().isEmpty()) {
      throw new IllegalArgumentException("Byte data cannot be null or empty");
    }

    String trimmed = data.trim();

    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
      return isValidHexString(trimmed);
    }

    // Проверяем как Base64
    try {
      byte[] decoded = java.util.Base64.getDecoder().decode(trimmed);
      String reencoded = java.util.Base64.getEncoder().encodeToString(decoded);
      if (!trimmed.equals(reencoded)) {
        return isValidHexString(trimmed);
      }
      return true;
    } catch (IllegalArgumentException e1) {
      return isValidHexString(trimmed);
    }
  }

  /**
   * Проверяет, является ли строка валидным hex представлением
   */
  private static boolean isValidHexString(String hex) {
    if (hex == null || hex.isEmpty()) {
      return false;
    }

    String hexValue = hex.startsWith("0x") || hex.startsWith("0X")
        ? hex.substring(2)
        : hex;

    if (hexValue.length() % 2 != 0) {
      throw new IllegalArgumentException("Hex string must have even length");
    }

    if (!hexValue.matches("[0-9a-fA-F]+")) {
      throw new IllegalArgumentException("Invalid hex characters");
    }

    return true;
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
          String trimmed = data.trim();
          try {
            return java.util.Base64.getDecoder().decode(trimmed);
          } catch (IllegalArgumentException e1) {
            if (isValidHexString(trimmed)) {
              String hexValue = trimmed.startsWith("0x") || trimmed.startsWith("0X")
                  ? trimmed.substring(2)
                  : trimmed;
              return hexStringToByteArray(hexValue);
            } else {
              throw new IllegalArgumentException(
                  "Invalid byte data. Expected Base64 or hex string");
            }
          }
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Invalid byte data: " + e.getMessage());
        }
      case JSON:
        validateJsonStrict(data);
        return data;
      case STRING:
        return data;
      default:
        throw new IllegalArgumentException("Unsupported data type: " + type);
    }
  }


  /**
   * Конвертирует hex строку в массив байтов
   */
  private static byte[] hexStringToByteArray(String hex) {
    if (hex.length() % 2 != 0) {
      throw new IllegalArgumentException("Hex string must have even length");
    }
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      int index = i * 2;
      String byteStr = hex.substring(index, index + 2);
      bytes[i] = (byte) Integer.parseInt(byteStr, 16);
    }
    return bytes;
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
            if (isValidByteArray(stringData)) {
              return stringData;
            }
          }
          throw new IllegalArgumentException("Invalid byte array data");

        case JSON:
          if (data instanceof String) {
            String jsonData = (String) data;
            objectMapper.readTree(jsonData);
            return jsonData;
          }
          throw new IllegalArgumentException("JSON data must be a string");

        case STRING:
          if (data instanceof String) {
            return (String) data;
          }
          return data.toString();

        default:
          return data.toString();
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Error formatting data for response: " + e.getMessage());
    }
  }
}