package com.mipt.controller;

public enum UserRole {
  ADMIN("admin"),
  READER("reader"),
  WRITER("writer");

  private final String value;

  UserRole(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  // Преобразование из строки в enum
  public static UserRole fromString(String value) {
    if (value == null) return null;

    for (UserRole role : UserRole.values()) {
      if (role.value.equalsIgnoreCase(value)) {
        return role;
      }
    }
    return null;
  }

  // Проверка существования роли
  public static boolean isValid(String value) {
    return fromString(value) != null;
  }

  // Получение всех допустимых значений
  public static String[] getAllValues() {
    UserRole[] roles = values();
    String[] result = new String[roles.length];
    for (int i = 0; i < roles.length; i++) {
      result[i] = roles[i].getValue();
    }
    return result;
  }
}
