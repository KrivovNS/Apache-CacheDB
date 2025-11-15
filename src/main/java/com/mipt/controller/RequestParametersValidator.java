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
  private static final int MAX_ADDED_USER_LENGTH = 50;

  // Допустимые параметры для каждого endpoint
  private static final Set<String> GET_CACHE_PARAMS = Set.of(
      "key", "type", "login", "password", "storage_token"
  );

  private static final Set<String> POST_CACHE_PARAMS = Set.of(
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

  // Основные методы валидации

  public ValidationResult validateGetCacheRequest(String uri) {
    QueryStringDecoder decoder = new QueryStringDecoder(uri);
    Map<String, List<String>> params = decoder.parameters();

    ValidationResult result = new ValidationResult();

    // Проверка на лишние параметры
    validateNoExtraParams(result, params, GET_CACHE_PARAMS, "GET cache");

    // Проверка на единственность значений
    validateSingleValueParams(result, params, GET_CACHE_PARAMS);

    // Обязательные параметры
    validateRequiredParam(result, params, "key");
    validateRequiredParam(result, params, "login");
    validateRequiredParam(result, params, "password");
    validateRequiredParam(result, params, "storage_token");

    // Проверка типа данных
    validateDataType(result, params);

    // Проверка длины параметров
    validateStringLength(result, getFirstParam(params, "key"), "key", 1, MAX_KEY_LENGTH);
    validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(result, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);
    validateStringLength(result, getFirstParam(params, "storage_token"), "storage_token", 10, MAX_TOKEN_LENGTH);

    return result;
  }

  public ValidationResult validatePostCacheRequest(String uri) {
    QueryStringDecoder decoder = new QueryStringDecoder(uri);
    Map<String, List<String>> params = decoder.parameters();

    ValidationResult result = new ValidationResult();

    // Проверка на лишние параметры
    validateNoExtraParams(result, params, POST_CACHE_PARAMS, "POST cache");

    // Проверка на единственность значений
    validateSingleValueParams(result, params, POST_CACHE_PARAMS);

    // Обязательные параметры
    validateRequiredParam(result, params, "key");
    validateRequiredParam(result, params, "login");
    validateRequiredParam(result, params, "password");
    validateRequiredParam(result, params, "storage_token");

    // Проверка типа данных
    validateDataType(result, params);

    // Проверка длины параметров
    validateStringLength(result, getFirstParam(params, "key"), "key", 1, MAX_KEY_LENGTH);
    validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(result, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);
    validateStringLength(result, getFirstParam(params, "storage_token"), "storage_token", 10, MAX_TOKEN_LENGTH);

    return result;
  }

  public ValidationResult validatePutCacheRequest(String uri) {
    // PUT имеет те же параметры, что и POST
    return validatePostCacheRequest(uri);
  }

  public ValidationResult validateDeleteCacheRequest(String uri) {
    QueryStringDecoder decoder = new QueryStringDecoder(uri);
    Map<String, List<String>> params = decoder.parameters();

    ValidationResult result = new ValidationResult();

    // Проверка на лишние параметры
    validateNoExtraParams(result, params, DELETE_CACHE_PARAMS, "DELETE cache");

    // Проверка на единственность значений
    validateSingleValueParams(result, params, DELETE_CACHE_PARAMS);

    // Обязательные параметры
    validateRequiredParam(result, params, "key");
    validateRequiredParam(result, params, "login");
    validateRequiredParam(result, params, "password");
    validateRequiredParam(result, params, "storage_token");

    // Проверка типа данных
    validateDataType(result, params);

    // Проверка длины параметров
    validateStringLength(result, getFirstParam(params, "key"), "key", 1, MAX_KEY_LENGTH);
    validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(result, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);
    validateStringLength(result, getFirstParam(params, "storage_token"), "storage_token", 10, MAX_TOKEN_LENGTH);

    return result;
  }

  public ValidationResult validateCreateStorageRequest(String uri) {
    QueryStringDecoder decoder = new QueryStringDecoder(uri);
    Map<String, List<String>> params = decoder.parameters();

    ValidationResult result = new ValidationResult();

    // Проверка на лишние параметры
    validateNoExtraParams(result, params, CREATE_STORAGE_PARAMS, "Create storage");

    // Проверка на единственность значений
    validateSingleValueParams(result, params, CREATE_STORAGE_PARAMS);

    // Обязательные параметры
    validateRequiredParam(result, params, "login");
    validateRequiredParam(result, params, "password");

    // Проверка длины и формата параметров
    validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(result, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);

    // Дополнительная проверка логина
    String login = getFirstParam(params, "login");
    if (login != null && !login.matches("^[a-zA-Z0-9_]+$")) {
      result.addError("Login can only contain letters, numbers and underscores");
    }

    return result;
  }

  public ValidationResult validateAddUserInStorage(String uri) {
    QueryStringDecoder decoder = new QueryStringDecoder(uri);
    Map<String, List<String>> params = decoder.parameters();

    ValidationResult result = new ValidationResult();

    // Проверка на лишние параметры
    validateNoExtraParams(result, params, ADD_USER_PARAMS, "Add user to storage");

    // Проверка на единственность значений
    validateSingleValueParams(result, params, ADD_USER_PARAMS);

    // Обязательные параметры
    validateRequiredParam(result, params, "login");
    validateRequiredParam(result, params, "password");
    validateRequiredParam(result, params, "addeduser");
    validateRequiredParam(result, params, "role");
    validateRequiredParam(result, params, "storage_token");

    // Проверка роли
    validateUserRole(result, params);

    // Проверка длины параметров
    validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
    validateStringLength(result, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);
    validateStringLength(result, getFirstParam(params, "addeduser"), "addeduser", 3, MAX_ADDED_USER_LENGTH);
    validateStringLength(result, getFirstParam(params, "storage_token"), "storage_token", 10, MAX_TOKEN_LENGTH);

    // Проверка что, не добавляем самого себя
    String login = getFirstParam(params, "login");
    String addedUser = getFirstParam(params, "addeduser");
    if (login != null && login.equals(addedUser)) {
      result.addError("Cannot add yourself to storage");
    }

    return result;
  }

  // Универсальный метод для валидации по HTTP методу
  public ValidationResult validateRequest(String method, String uri) {
    switch (method.toUpperCase()) {
      case "GET":
        return validateGetCacheRequest(uri);
      case "POST":
        return validatePostCacheRequest(uri);
      case "PUT":
        return validatePutCacheRequest(uri);
      case "DELETE":
        return validateDeleteCacheRequest(uri);
      default:
        ValidationResult result = new ValidationResult();
        result.addError("Unsupported HTTP method: " + method);
        return result;
    }
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

  private void validateDataType(ValidationResult result, Map<String, List<String>> params) {
    if (params.containsKey("type")) {
      String type = getFirstParam(params, "type");
      if (!DataType.isValid(type)) {
        result.addError("Invalid type. Allowed: " + String.join(", ", DataType.getAllValues()));
      }
    }
  }

  private void validateUserRole(ValidationResult result, Map<String, List<String>> params) {
    if (params.containsKey("role")) {
      String role = getFirstParam(params, "role");
      if (!UserRole.isValid(role)) {
        result.addError("Invalid role. Allowed: " + String.join(", ", UserRole.getAllValues()));
      }
    }
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
          result.addError("Parameter '" + paramName + "' should have only one value, but got " + values.size());
        }
      }
    }
  }

  private String getFirstParam(Map<String, List<String>> params, String paramName) {
    List<String> values = params.get(paramName);
    return (values != null && !values.isEmpty()) ? values.get(0) : null;
  }

  private void validateStringLength(ValidationResult result, String value, String fieldName, int min, int max) {
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

  private void validateRequiredParam(ValidationResult result, Map<String, List<String>> params, String paramName) {
    String value = getFirstParam(params, paramName);
    if (value == null || value.trim().isEmpty()) {
      result.addError("Parameter '" + paramName + "' is required and cannot be empty");
    }
  }
}