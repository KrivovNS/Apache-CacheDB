package com.mipt.model;

public enum MaxMemoryPolicy {
  NOEVICTION("noeviction"),
  ALLKEYSLRU("allkeys-lru"),
  VOLATILELRU("volatile-lru"),
  ALLKEYSLFU("allkeys-lfu"),
  VOLATILELFU("volatile-lfu");


  private final String value;

  MaxMemoryPolicy(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static MaxMemoryPolicy fromString(String type) {
    if (type == null) {
      return null;
    }

    for (MaxMemoryPolicy MaxMemoryPolicy : MaxMemoryPolicy.values()) {
      if (MaxMemoryPolicy.getValue().equals(type.toLowerCase())) {
        return MaxMemoryPolicy;
      }
    }
    return null;
  }

  public static boolean isValid(String type) {
    return fromString(type) != null;
  }

  public static String[] getAllValues() {
    MaxMemoryPolicy[] types = values();
    String[] values = new String[types.length];
    for (int i = 0; i < types.length; i++) {
      values[i] = types[i].getValue();
    }
    return values;
  }
}
