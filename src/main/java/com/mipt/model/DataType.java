package com.mipt.model;

public enum DataType {
  JSON("json"),
  BYTES("byte[]"),
  STRING("string");

  private final String value;

  DataType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static DataType fromString(String type) {
    if (type == null) return null;

    for (DataType dataType : DataType.values()) {
      if (dataType.getValue().equals(type.toLowerCase())) {
        return dataType;
      }
    }
    return null;
  }

  public static boolean isValid(String type) {
    return fromString(type) != null;
  }

  public static String[] getAllValues() {
    DataType[] types = values();
    String[] values = new String[types.length];
    for (int i = 0; i < types.length; i++) {
      values[i] = types[i].getValue();
    }
    return values;
  }
}