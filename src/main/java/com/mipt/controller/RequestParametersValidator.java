package com.mipt.controller;

import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RequestParametersValidator {

  // Константы максимальных длин
  private static final int MAX_TOKEN_LENGTH = 255;
  private static final int MAX_PASSWORD_LENGTH = 255;
  private static final int MAX_KEY_LENGTH = 255;
  private static final int MAX_LOGIN_LENGTH = 50;
  private static final int MAX_ADDED_USER_LENGTH = 50;

  // Допустимые параметры для каждого endpoint
  private static final Set<String> GET_CACHE_PARAMS = Set.of(
      "key", "type", "login", "password", "storage_token"
  );

  private static final Set<String> POST_CACHE_PARAMS = Set.of(
      "key", "type", "login", "password", "storage_token"
  );

  private static final Set<String> PUT_CACHE_PARAMS = Set.of(
      "key", "type", "login", "password", "storage_token"
  );

  private static final Set<String> DELETE_CACHE_PARAMS = Set.of(
      "key", "type", "login", "password", "storage_token"
  );

  private static final Set<String> CREATE_STORAGE_PARAMS = Set.of(
      "login", "password"
  );

  private static final Set<String> ADD_USER_PARAMS = Set.of(
      "login", "password", "addeduser", "role", "storage_token"
  );

  // Основной метод валидации для NettyHandler
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
    Map<String, List<String>> params = parseUriParameters(uri);

    // Проверка метода
    if (!isValidCacheMethod(method)) {
      errors.add("Invalid method for cache endpoint: " + method);
      return;
    }

    // Определяем допустимые параметры в зависимости от метода
    Set<String> allowedParams = getAllowedCacheParams(method);

    // Проверка на лишние параметры
    validateNoExtraParams(errors, params, allowedParams, method + " cache");

    // Проверка на единственность значений
    validateSingleValueParams(errors, params, allowedParams);

    // Обязательные параметры
    validateRequiredParam(errors, params, "key");
    validateRequiredParam(errors, params, "login");
    validateRequiredParam(errors, params, "password");
    validateRequiredParam(errors, params, "storage_token");
    validateRequiredParam(errors, params, "type");

    // Проверка типа данных
    if (params.containsKey("type")) {
      String type = getFirstParam(params, "type");
      if (!DataType.isValid(type)) {
        errors.add("Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
      }
    }

    // Проверка длины параметров
    validateStringLength(errors, getFirstParam(params, "key"), "key", 1, MAX_KEY_LENGTH);
    validateStringLength(errors, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(errors, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);
    validateStringLength(errors, getFirstParam(params, "storage_token"), "storage_token", 10, MAX_TOKEN_LENGTH);
  }

  private void validateStorageRequest(String method, String uri, List<String> errors) {
    Map<String, List<String>> params = parseUriParameters(uri);

    Set<String> allowedParams;
    switch (method.toUpperCase()) {
      case "POST":
        allowedParams = CREATE_STORAGE_PARAMS;
        break;
      case "PUT":
        allowedParams = ADD_USER_PARAMS;
        break;
      default:
        errors.add("Unsupported method for storage endpoint: " + method);
        return;
    }

    // Проверка на лишние параметры
    validateNoExtraParams(errors, params, allowedParams, method + " storage");

    // Проверка на единственность значений
    validateSingleValueParams(errors, params, allowedParams);

    // Обязательные параметры
    validateRequiredParam(errors, params, "login");
    validateRequiredParam(errors, params, "password");

    if ("PUT".equals(method)) {
      validateRequiredParam(errors, params, "addeduser");
      validateRequiredParam(errors, params, "role");
      validateRequiredParam(errors, params, "storage_token");

      // Проверка роли
      if (params.containsKey("role")) {
        String role = getFirstParam(params, "role");
        if (!UserRole.isValid(role)) {
          errors.add("Invalid role. Allowed: " + String.join(", ", UserRole.getAllValues()));
        }
      }

      // Проверка длины параметров
      validateStringLength(errors, getFirstParam(params, "addeduser"), "addeduser", 3, MAX_ADDED_USER_LENGTH);
      validateStringLength(errors, getFirstParam(params, "storage_token"), "storage_token", 10, MAX_TOKEN_LENGTH);

      // Проверка что не добавляем самого себя
      String login = getFirstParam(params, "login");
      String addedUser = getFirstParam(params, "addeduser");
      if (login != null && login.equals(addedUser)) {
        errors.add("Cannot add yourself to storage");
      }
    }

    // Проверка длины параметров
    validateStringLength(errors, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(errors, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);

    // Дополнительная проверка логина
    String login = getFirstParam(params, "login");
    if (login != null && !login.matches("^[a-zA-Z0-9_]+$")) {
      errors.add("Login can only contain letters, numbers and underscores");
    }
  }

  // Специализированные методы для более детальной валидации

  public ValidationResult validateGetCacheRequest(String uri) {
    return validateCacheRequestByMethod("GET", uri);
  }

  public ValidationResult validatePostCacheRequest(String uri) {
    return validateCacheRequestByMethod("POST", uri);
  }

  public ValidationResult validatePutCacheRequest(String uri) {
    return validateCacheRequestByMethod("PUT", uri);
  }

  public ValidationResult validateDeleteCacheRequest(String uri) {
    return validateCacheRequestByMethod("DELETE", uri);
  }

  public ValidationResult validateCreateStorageRequest(String uri) {
    List<String> errors = new ArrayList<>();
    Map<String, List<String>> params = parseUriParameters(uri);

    validateNoExtraParams(errors, params, CREATE_STORAGE_PARAMS, "Create storage");
    validateSingleValueParams(errors, params, CREATE_STORAGE_PARAMS);
    validateRequiredParam(errors, params, "login");
    validateRequiredParam(errors, params, "password");
    validateStringLength(errors, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(errors, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);

    String login = getFirstParam(params, "login");
    if (login != null && !login.matches("^[a-zA-Z0-9_]+$")) {
      errors.add("Login can only contain letters, numbers and underscores");
    }

    return new ValidationResult(errors.isEmpty(), errors);
  }

  public ValidationResult validateAddUserInStorage(String uri) {
    List<String> errors = new ArrayList<>();
    Map<String, List<String>> params = parseUriParameters(uri);

    validateNoExtraParams(errors, params, ADD_USER_PARAMS, "Add user to storage");
    validateSingleValueParams(errors, params, ADD_USER_PARAMS);
    validateRequiredParam(errors, params, "login");
    validateRequiredParam(errors, params, "password");
    validateRequiredParam(errors, params, "addeduser");
    validateRequiredParam(errors, params, "role");
    validateRequiredParam(errors, params, "storage_token");

    validateUserRole(errors, params);
    validateStringLength(errors, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(errors, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);
    validateStringLength(errors, getFirstParam(params, "addeduser"), "addeduser", 3, MAX_ADDED_USER_LENGTH);
    validateStringLength(errors, getFirstParam(params, "storage_token"), "storage_token", 10, MAX_TOKEN_LENGTH);

    String login = getFirstParam(params, "login");
    String addedUser = getFirstParam(params, "addeduser");
    if (login != null && login.equals(addedUser)) {
      errors.add("Cannot add yourself to storage");
    }

    return new ValidationResult(errors.isEmpty(), errors);
  }

  // Вспомогательные методы для извлечения параметров

  public Map<String, List<String>> parseUriParameters(String uri) {
    QueryStringDecoder decoder = new QueryStringDecoder(uri);
    return decoder.parameters();
  }

  public String getParameter(String uri, String paramName) {
    Map<String, List<String>> params = parseUriParameters(uri);
    return getFirstParam(params, paramName);
  }

  // Приватные вспомогательные методы

  private ValidationResult validateCacheRequestByMethod(String method, String uri) {
    List<String> errors = new ArrayList<>();
    validateCacheRequest(method, uri, errors);
    return new ValidationResult(errors.isEmpty(), errors);
  }

  private Set<String> getAllowedCacheParams(String method) {
    switch (method.toUpperCase()) {
      case "GET": return GET_CACHE_PARAMS;
      case "POST": return POST_CACHE_PARAMS;
      case "PUT": return PUT_CACHE_PARAMS;
      case "DELETE": return DELETE_CACHE_PARAMS;
      default: return Set.of();
    }
  }

  private boolean isValidCacheMethod(String method) {
    return "GET".equals(method) || "POST".equals(method) ||
        "PUT".equals(method) || "DELETE".equals(method);
  }

  private void validateNoExtraParams(List<String> errors,
      Map<String, List<String>> params,
      Set<String> allowedParams,
      String endpointName) {
    for (String paramName : params.keySet()) {
      if (!allowedParams.contains(paramName)) {
        errors.add("Unexpected parameter '" + paramName + "' for " + endpointName +
            ". Allowed parameters: " + String.join(", ", allowedParams));
      }
    }
  }

  private void validateSingleValueParams(List<String> errors,
      Map<String, List<String>> params,
      Set<String> paramNames) {
    for (String paramName : paramNames) {
      if (params.containsKey(paramName)) {
        List<String> values = params.get(paramName);
        if (values != null && values.size() > 1) {
          errors.add("Parameter '" + paramName + "' should have only one value, but got " + values.size());
        }
      }
    }
  }

  private String getFirstParam(Map<String, List<String>> params, String paramName) {
    List<String> values = params.get(paramName);
    return (values != null && !values.isEmpty()) ? values.get(0) : null;
  }

  private void validateStringLength(List<String> errors, String value, String fieldName, int min, int max) {
    if (value == null) {
      return;
    }

    if (value.length() < min) {
      errors.add(fieldName + " must be at least " + min + " characters");
    }

    if (value.length() > max) {
      errors.add(fieldName + " must be at most " + max + " characters");
    }
  }

  private void validateRequiredParam(List<String> errors, Map<String, List<String>> params, String paramName) {
    String value = getFirstParam(params, paramName);
    if (value == null || value.trim().isEmpty()) {
      errors.add("Parameter '" + paramName + "' is required and cannot be empty");
    }
  }

  private void validateUserRole(List<String> errors, Map<String, List<String>> params) {
    if (params.containsKey("role")) {
      String role = getFirstParam(params, "role");
      if (!UserRole.isValid(role)) {
        errors.add("Invalid role. Allowed: " + String.join(", ", UserRole.getAllValues()));
      }
    }
  }

  // Класс результата валидации
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

  // Простой парсер URI для обратной совместимости
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
}