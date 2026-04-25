package com.mipt.controller;

import com.mipt.model.DataType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public class MemoryCalculator {

  private MemoryCalculator() {
  }

  public static long calculateBytes(DataType type, String data) {
    if (data == null) {
      return 0;
    }

    return switch (type) {
      case STRING, JSON -> data.getBytes(StandardCharsets.UTF_8).length;
      case BYTES -> decodeBytesLength(data);
    };
  }

  public static long calculateBits(DataType type, String data) {
    return calculateBytes(type, data) * 8;
  }

  public static long calculateEntrySizeBytes(String key, DataType dataType, Object storedValue) {
    Objects.requireNonNull(dataType, "dataType");
    if (key == null) {
      throw new IllegalArgumentException("Key cannot be null");
    }

    long keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
    long payloadBytes = calculateStoredPayloadBytes(dataType, storedValue);
    return safeAdd(keyBytes, payloadBytes);
  }

  public static long calculateStoredPayloadBytes(DataType dataType, Object storedValue) {
    Objects.requireNonNull(dataType, "dataType");
    if (storedValue == null) {
      throw new IllegalArgumentException("Stored value cannot be null");
    }

    return switch (dataType) {
      case STRING, JSON -> {
        if (!(storedValue instanceof String stringValue)) {
          throw new IllegalArgumentException("Stored value must be string for type " + dataType);
        }
        yield stringValue.getBytes(StandardCharsets.UTF_8).length;
      }
      case BYTES -> {
        if (!(storedValue instanceof byte[] bytes)) {
          throw new IllegalArgumentException("Stored value must be byte[] for BYTES type");
        }
        yield bytes.length;
      }
    };
  }

  private static int decodeBytesLength(String data) {
    String trimmed = data.trim();
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
      String hex = trimmed.substring(2);
      if (hex.length() % 2 != 0) {
        throw new IllegalArgumentException("Hex byte string must have even length");
      }
      return hex.length() / 2;
    }

    return Base64.getDecoder().decode(trimmed).length;
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Entry size overflow");
    }
  }
}
