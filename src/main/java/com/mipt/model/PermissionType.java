package com.mipt.model;

public enum PermissionType {
  SUPERADMIN("superadmin"),
  ADMIN("admin"),
  READER("reader"),
  ISOLATED("isolated");

  private final String value;

  PermissionType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static PermissionType fromString(String type) {
    if (type == null) return null;

    for (PermissionType PermissionType : PermissionType.values()) {
      if (PermissionType.getValue().equals(type.toLowerCase())) {
        return PermissionType;
      }
    }
    return null;
  }

  public static boolean isValid(String type) {
    return fromString(type) != null;
  }

  public static String[] getAllValues() {
    PermissionType[] types = values();
    String[] values = new String[types.length];
    for (int i = 0; i < types.length; i++) {
      values[i] = types[i].getValue();
    }
    return values;
  }
}