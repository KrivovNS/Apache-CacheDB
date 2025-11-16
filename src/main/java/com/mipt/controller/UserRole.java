package com.mipt.controller;

public enum UserRole {
  READER("reader"),
  WRITER("writer"),
  ADMIN("admin");

  private final String value;

  UserRole(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static UserRole fromString(String role) {
    if (role == null) return null;

    for (UserRole userRole : UserRole.values()) {
      if (userRole.getValue().equals(role.toLowerCase())) {
        return userRole;
      }
    }
    return null;
  }

  public static boolean isValid(String role) {
    return fromString(role) != null;
  }

  public static String[] getAllValues() {
    UserRole[] roles = values();
    String[] values = new String[roles.length];
    for (int i = 0; i < roles.length; i++) {
      values[i] = roles[i].getValue();
    }
    return values;
  }
}