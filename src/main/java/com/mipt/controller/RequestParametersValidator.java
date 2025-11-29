package com.mipt.controller;

import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RequestParametersValidator {

  // Константы максимальных длин
  private static final int MAX_TOKEN_LENGTH = 255;
  private static final int MAX_PASSWORD_LENGTH = 255;
  private static final int MAX_KEY_LENGTH = 255;
  private static final int MAX_LOGIN_LENGTH = 50;

  // Допустимые параметры для каждого endpoint
  private static final Set<String> GET_CACHE_PARAMS = Set.of(
      "session_token", "key", "type"
  );

  private static final Set<String> POST_CACHE_PARAMS = Set.of(
      "session_token", "key", "type"
  );

  private static final Set<String> PUT_CACHE_PARAMS = Set.of(
      "session_token", "key", "type"
  );

  private static final Set<String> DELETE_CACHE_PARAMS = Set.of(
      "session_token", "key", "type"
  );

  private static final Set<String> CREATE_STORAGE_PARAMS = Set.of(
      "login", "password"
  );

  // Основной метод валидации для NettyHandler
  public ValidationResult validateRequest(String method, String uri) {
    ValidationResult result = new ValidationResult();

    if (uri.startsWith("/cache")) {
      validateCacheRequest(method, uri, result);
    } else if (uri.startsWith("/storage")) {
      validateStorageRequest(method, uri, result);
    }

    return result;
  }

  private void validateCacheRequest(String method, String uri, ValidationResult result) {
    Map<String, List<String>> params = parseUriParameters(uri);

    // Проверка метода
    if (!isValidCacheMethod(method)) {
      result.addError("Invalid method for cache endpoint: " + method);
      return;
    }

    // Определяем допустимые параметры в зависимости от метода
    Set<String> allowedParams = getAllowedCacheParams(method);

    // Проверка на лишние параметры
    validateNoExtraParams(result, params, allowedParams, method + " cache");

    // Проверка на единственность значений
    validateSingleValueParams(result, params, allowedParams);

    // Обязательные параметры
    for (String requiredParam : allowedParams) {
      validateRequiredParam(result, params, requiredParam);
    }

    // Проверка типа данных
    if (params.containsKey("type")) {
      String type = getFirstParam(params, "type");
      if (!DataType.isValid(type)) {
        result.addError(
            "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
      }
    }

    // Проверка длины параметров
    validateStringLength(result, getFirstParam(params, "session_token"), "session_token", 10,
        MAX_TOKEN_LENGTH);
    validateStringLength(result, getFirstParam(params, "key"), "key", 1, MAX_KEY_LENGTH);

    // Проверка формата session_token
    String sessionToken = getFirstParam(params, "session_token");
    if (sessionToken != null && !sessionToken.matches("^[a-fA-F0-9-]+$")) {
      result.addError("Session token can only contain letters, numbers and underscores");
    }

    // Проверка формата key
    String key = getFirstParam(params, "key");
    if (key != null && key.trim().isEmpty()) {
      result.addError("Key cannot be empty or contain only whitespace");
    }
  }

  private void validateStorageRequest(String method, String uri, ValidationResult result) {
    Map<String, List<String>> params = parseUriParameters(uri);

    if (!"POST".equalsIgnoreCase(method)) {
      result.addError(
          "Unsupported method for storage endpoint: " + method + ". Only POST is allowed");
      return;
    }

    Set<String> allowedParams = CREATE_STORAGE_PARAMS;

    // Проверка на лишние параметры
    validateNoExtraParams(result, params, allowedParams, method + " storage");

    // Проверка на единственность значений
    validateSingleValueParams(result, params, allowedParams);

    // Обязательные параметры
    validateRequiredParam(result, params, "login");
    validateRequiredParam(result, params, "password");

    // Проверка длины параметров
    validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(result, getFirstParam(params, "password"), "password", 6,
        MAX_PASSWORD_LENGTH);

    // Дополнительная проверка логина
    String login = getFirstParam(params, "login");
    if (login != null && !login.matches("^[a-zA-Z0-9_]+$")) {
      result.addError("Login can only contain letters, numbers and underscores");
    }

    // Проверка пароля на безопасность
    String password = getFirstParam(params, "password");
    if (password != null && password.length() < 6) {
      result.addError("Password must be at least 6 characters long");
    }
  }

  // Вспомогательные методы для извлечения параметров

  public Map<String, List<String>> parseUriParameters(String uri) {
    QueryStringDecoder decoder = new QueryStringDecoder(uri);
    return decoder.parameters();
  }

  // Приватные вспомогательные методы

  private Set<String> getAllowedCacheParams(String method) {
    switch (method.toUpperCase()) {
      case "GET":
        return GET_CACHE_PARAMS;
      case "POST":
        return POST_CACHE_PARAMS;
      case "PUT":
        return PUT_CACHE_PARAMS;
      case "DELETE":
        return DELETE_CACHE_PARAMS;
      default:
        return Set.of();
    }
  }

  private boolean isValidCacheMethod(String method) {
    return "GET".equals(method) || "POST".equals(method) ||
        "PUT".equals(method) || "DELETE".equals(method);
  }

  private void validateNoExtraParams(ValidationResult result,
      Map<String, List<String>> params,
      Set<String> allowedParams,
      String endpointName) {
    for (String paramName : params.keySet()) {
      if (!allowedParams.contains(paramName)) {
        result.addError("Unexpected parameter '" + paramName + "' for " + endpointName +
            ". Allowed parameters: " + String.join(", ", allowedParams));
      }
    }
  }

  private void validateSingleValueParams(ValidationResult result,
      Map<String, List<String>> params,
      Set<String> paramNames) {
    for (String paramName : paramNames) {
      if (params.containsKey(paramName)) {
        List<String> values = params.get(paramName);
        if (values != null && values.size() > 1) {
          result.addError(
              "Parameter '" + paramName + "' should have only one value, but got " + values.size());
        }
      }
    }
  }

  private String getFirstParam(Map<String, List<String>> params, String paramName) {
    List<String> values = params.get(paramName);
    return (values != null && !values.isEmpty()) ? values.get(0) : null;
  }

  private void validateStringLength(ValidationResult result, String value, String fieldName,
      int min, int max) {
    if (value == null) {
      return;
    }

    if (value.length() < min) {
      result.addError(fieldName + " must be at least " + min + " characters");
    }

    if (value.length() > max) {
      result.addError(fieldName + " must be at most " + max + " characters");
    }
  }

  private void validateRequiredParam(ValidationResult result, Map<String, List<String>> params,
      String paramName) {
    String value = getFirstParam(params, paramName);
    if (value == null || value.trim().isEmpty()) {
      result.addError("Parameter '" + paramName + "' is required and cannot be empty");
    }
  }
}