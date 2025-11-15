package com.mipt.controller;

public enum DataType {
  STRING("string"),
  JSON("json"),
  BYTES("bytes");

  private final String value;

  DataType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  // Преобразование из строки в enum
  public static DataType fromString(String value) {
    if (value == null) return null;

    for (DataType type : DataType.values()) {
      if (type.value.equalsIgnoreCase(value)) {
        return type;
      }
    }
    return null;
  }

  // Проверка существования типа
  public static boolean isValid(String value) {
    return fromString(value) != null;
  }

  // Получение всех допустимых значений
  public static String[] getAllValues() {
    DataType[] types = values();
    String[] result = new String[types.length];
    for (int i = 0; i < types.length; i++) {
      result[i] = types[i].getValue();
    }
    return result;
  }
}