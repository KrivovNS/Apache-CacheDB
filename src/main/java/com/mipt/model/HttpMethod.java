package com.mipt.model;

public enum HttpMethod {
  GET("GET"),
  POST("POST"),
  PUT("PUT"),
  DELETE("DELETE");

  private final String method;

  HttpMethod(String method) {
    this.method = method;
  }

  public String getMethod() {
    return method;
  }

  public static HttpMethod fromString(String method) {
    if (method == null) return null;

    for (HttpMethod httpMethod : HttpMethod.values()) {
      if (httpMethod.getMethod().equalsIgnoreCase(method)) {
        return httpMethod;
      }
    }
    return null;
  }

  public static boolean isValid(String method) {
    return fromString(method) != null;
  }

  public static String[] getAllValues() {
    HttpMethod[] methods = values();
    String[] values = new String[methods.length];
    for (int i = 0; i < methods.length; i++) {
      values[i] = methods[i].getMethod();
    }
    return values;
  }

  public boolean isGet() {
    return this == GET;
  }

  public boolean isPost() {
    return this == POST;
  }

  public boolean isPut() {
    return this == PUT;
  }

  public boolean isDelete() {
    return this == DELETE;
  }

  public boolean isCacheMethod() {
    return this == GET || this == POST || this == PUT || this == DELETE;
  }

  public boolean isWriteMethod() {
    return this == POST || this == PUT;
  }

  public boolean isReadMethod() {
    return this == GET;
  }
}