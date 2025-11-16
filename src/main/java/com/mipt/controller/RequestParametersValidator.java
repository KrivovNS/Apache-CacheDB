package com.mipt.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RequestParametersValidator {

  public ValidationResult validateRequest(String method, String uri) {
    List<String> errors = new ArrayList<>();

    if (uri.startsWith("/cache")) {
      validateCacheRequest(method, uri, errors);
    } else if (uri.startsWith("/storage")) {
      validateStorageRequest(method, uri, errors);
    }

    return new ValidationResult(errors.isEmpty(), errors);
  }

  private void validateCacheRequest(String method, String uri, List<String> errors) {
    Map<String, String> params = parseUri(uri);

    // Обязательные параметры для всех cache операций
    if (!params.containsKey("storage_token")) {
      errors.add("Missing required parameter: storage_token");
    }
    if (!params.containsKey("type")) {
      errors.add("Missing required parameter: type");
    } else if (!DataType.isValid(params.get("type"))) {
      errors.add("Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
    }
    if (!params.containsKey("key")) {
      errors.add("Missing required parameter: key");
    }
    if (!params.containsKey("login")) {
      errors.add("Missing required parameter: login");
    }
    if (!params.containsKey("password")) {
      errors.add("Missing required parameter: password");
    }

    // Валидация метода
    if (!isValidCacheMethod(method)) {
      errors.add("Invalid method for cache endpoint: " + method);
    }
  }

  private void validateStorageRequest(String method, String uri, List<String> errors) {
    Map<String, String> params = parseUri(uri);

    if (!params.containsKey("login")) {
      errors.add("Missing required parameter: login");
    }
    if (!params.containsKey("password")) {
      errors.add("Missing required parameter: password");
    }

    if ("PUT".equals(method)) {
      if (!params.containsKey("addeduser")) {
        errors.add("Missing required parameter: addeduser");
      }
      if (!params.containsKey("role")) {
        errors.add("Missing required parameter: role");
      } else if (!UserRole.isValid(params.get("role"))) {
        errors.add("Invalid role. Allowed: " + String.join(", ", UserRole.getAllValues()));
      }
      if (!params.containsKey("storage_token")) {
        errors.add("Missing required parameter: storage_token");
      }
    }
  }

  private boolean isValidCacheMethod(String method) {
    return "GET".equals(method) || "POST".equals(method) ||
        "PUT".equals(method) || "DELETE".equals(method);
  }

  private Map<String, String> parseUri(String uri) {
    Map<String, String> params = new HashMap<>();

    if (uri.contains("?")) {
      String query = uri.substring(uri.indexOf("?") + 1);
      String[] pairs = query.split("&");
      for (String pair : pairs) {
        String[] keyValue = pair.split("=");
        if (keyValue.length == 2) {
          params.put(keyValue[0], keyValue[1]);
        }
      }
    }

    return params;
  }

  public static class ValidationResult {
    private final boolean valid;
    private final List<String> errors;

    public ValidationResult(boolean valid, List<String> errors) {
      this.valid = valid;
      this.errors = errors;
    }

    public boolean getValid() {
      return valid;
    }

    public List<String> getErrors() {
      return errors;
    }

    public String getErrorsAsString() {
      return String.join(", ", errors);
    }
  }
}