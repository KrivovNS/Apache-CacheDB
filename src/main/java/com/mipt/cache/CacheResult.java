package com.mipt.cache;

public class CacheResult {
  private final boolean success;
  private final String message;
  private final Object data;

  private CacheResult(boolean success, String message, Object data) {
    this.success = success;
    this.message = message;
    this.data = data;
  }

  private CacheResult(boolean success, String message) {
    this.success = success;
    this.message = message;
    data = null;
  }

  public static CacheResult success(Object data) {
    return new CacheResult(true, "Operation completed successfully", data);
  }

  public static CacheResult success() {
    return new CacheResult(true, "Operation completed successfully");
  }

  public static CacheResult error(String message) {
    return new CacheResult(false, message, null);
  }

  public boolean isSuccess() { return success; }
  public String getMessage() { return message; }
  public Object getData() { return data; }

  @Override
  public String toString() {
    return "CacheResult{" +
        "success=" + success +
        ", message='" + message + '\'' +
        ", data=" + data +
        '}';
  }
}
