package com.mipt.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;

public class DataTypeValidator {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
    objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, false);
    objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, false);
  }

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

      if (trimmed.isEmpty()) {
        return false;
      }

      objectMapper.readTree(trimmed);
      return true;

    } catch (JsonProcessingException e) {
      System.err.println("Invalid JSON: " + e.getMessage());
      return false;
    } catch (Exception e) {
      System.err.println("Error validating JSON: " + e.getMessage());
      return false;
    }
  }

  public static boolean isValidJSONDetailed(String data) {
    try {
      String trimmed = data.trim();

      if (trimmed.isEmpty()) {
        return false;
      }

      char firstChar = trimmed.charAt(0);
      char lastChar = trimmed.charAt(trimmed.length() - 1);

      if (!((firstChar == '{' && lastChar == '}') ||
          (firstChar == '[' && lastChar == ']'))) {
        return false;
      }

      objectMapper.readTree(trimmed);
      return true;

    } catch (Exception e) {
      return false;
    }
  }

  public static Object parseJSON(String data) {
    try {
      return objectMapper.readTree(data.trim());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
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
          return objectMapper.readTree(data.trim());
        } catch (Exception e) {
          throw new IllegalArgumentException("Invalid JSON format: " + e.getMessage());
        }
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

          if (data instanceof com.fasterxml.jackson.databind.JsonNode) {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
          } else {

            try {
              JsonNode jsonNode = objectMapper.readTree(data.toString());
              return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            } catch (Exception e) {

              return data.toString();
            }
          }

        case STRING:
        default:
          return data.toString();
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Error formatting data for response: " + e.getMessage());
    }
  }


  public static String toJSONString(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Error converting object to JSON: " + e.getMessage());
    }
  }

  public static <T> T parseJSON(String data, Class<T> valueType) {
    try {
      return objectMapper.readValue(data, valueType);
    } catch (Exception e) {
      throw new IllegalArgumentException("Error parsing JSON to " + valueType.getSimpleName() + ": " + e.getMessage());
    }
  }
}