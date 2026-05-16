package com.mipt.controller;

import com.mipt.model.DataType;
import com.mipt.model.PermissionType;
import com.mipt.model.MaxMemoryPolicy;
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
    private static final int MAX_SQL_LENGTH = 8192;

    // Допустимые параметры для каждого endpoint
    private static final Set<String> GET_CACHE_PARAMS = Set.of(
            "session_token", "key"
    );

    private static final Set<String> GET_CACHE_PARAMS_WITH_SQL = Set.of(
            "session_token", "sql", "key"
    );

    private static final Set<String> POST_CACHE_PARAMS = Set.of(
            "session_token", "key", "type", "ttl"
    );

    private static final Set<String> PUT_CACHE_PARAMS = Set.of(
            "session_token", "key", "type", "ttl"
    );

    private static final Set<String> DELETE_CACHE_PARAMS = Set.of(
            "session_token", "key"
    );

    private static final Set<String> GET_AUTH_PARAMS = Set.of(
            "login", "password"
    );

    private static final Set<String> POST_USER_PARAMS = Set.of(
            "session_token", "login", "password", "permission"
    );

    private static final Set<String> PUT_USER_PARAMS = Set.of(
            "session_token", "login", "new_login", "password", "permission"
    );

    private static final Set<String> DELETE_USER_PARAMS = Set.of(
            "session_token", "login"
    );

    private static final Set<String> PUT_CONFIG_PARAMS = Set.of(
            "session_token", "max_memory_policy", "max_storage_memory", "persistence"
    );

    // Основной метод валидации для NettyHandler
    public ValidationResult validateRequest(String method, String uri) {
        ValidationResult result = new ValidationResult();

        if (uri.startsWith("/cache")) {
            validateCacheRequest(method, uri, result);
        } else if (uri.startsWith("/auth")) {
            validateAuthRequest(method, uri, result);
        } else if (uri.startsWith("/user")) {
            validateUserRequest(method, uri, result);
        } else if (uri.startsWith("/configuration")) {
            validateConfigRequest(method, uri, result);
        } else {
            result.addError("Unknown endpoint: " + uri);
        }

        return result;
    }

    private void validateCacheRequest(String method, String uri, ValidationResult result) {
        Map<String, List<String>> params = parseUriParameters(uri);

        if (!isValidCacheMethod(method)) {
            result.addError("Invalid method for cache endpoint: " + method);
            return;
        }

        Set<String> allowedParams = getAllowedCacheParams(method, params);

        validateNoExtraParams(result, params, allowedParams, method + " cache");

        if (!result.getValid()) {
            return;
        }

        switch (method.toUpperCase()) {
            case "GET":
                boolean hasSql = params.containsKey("sql") && getFirstParam(params, "sql") != null;
                boolean hasKey = params.containsKey("key") && getFirstParam(params, "key") != null;

                if (!hasSql && !hasKey) {
                    result.addError("Parameter 'key' or 'sql' is required for GET");
                }

                if (hasSql) {
                    validateStringLength(result, getFirstParam(params, "sql"), "sql", 1, MAX_SQL_LENGTH);
                }

                validateRequiredParam(result, params, "session_token");
                break;
            case "POST":
            case "PUT":
                validateRequiredParam(result, params, "session_token");
                validateRequiredParam(result, params, "key");
                validateRequiredParam(result, params, "type");
                break;
            case "DELETE":
                validateRequiredParam(result, params, "session_token");
                validateRequiredParam(result, params, "key");
                break;
        }

        validateSingleValueParams(result, params, allowedParams);

        if (!result.getValid()) {
            return;
        }

        if (params.containsKey("type")) {
            String type = getFirstParam(params, "type");
            if (!DataType.isValid(type)) {
                result.addError(
                        "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
            }
        }

        if (params.containsKey("ttl")) {
            String ttl = getFirstParam(params, "ttl");
            if (ttl != null && !ttl.isEmpty()) {
                if (!isValidTTLFormat(ttl)) {
                    result.addError("Invalid TTL format. Valid formats: <number>[ms|s|m|h|d] or plain milliseconds");
                }
            }
        }

        validateStringLength(result, getFirstParam(params, "session_token"), "session_token", 10, MAX_TOKEN_LENGTH);

        if (params.containsKey("key")) {
            validateStringLength(result, getFirstParam(params, "key"), "key", 1, MAX_KEY_LENGTH);

            String key = getFirstParam(params, "key");
            if (key != null && key.trim().isEmpty()) {
                result.addError("Key cannot be empty or contain only whitespace");
            }
        }

        String sessionToken = getFirstParam(params, "session_token");
        if (sessionToken != null && !sessionToken.matches("^[a-fA-F0-9-]+$")) {
            result.addError("Session token can only contain letters, numbers and hyphens");
        }
    }

    private void validateAuthRequest(String method, String uri, ValidationResult result) {
        Map<String, List<String>> params = parseUriParameters(uri);

        if (!"GET".equalsIgnoreCase(method)) {
            result.addError(
                    "Unsupported method for auth endpoint: " + method + ". Only GET is allowed");
            return;
        }

        Set<String> allowedParams = GET_AUTH_PARAMS;

        validateNoExtraParams(result, params, allowedParams, method + " auth");

        if (!result.getValid()) {
            return;
        }

        validateRequiredParam(result, params, "login");
        validateRequiredParam(result, params, "password");

        validateSingleValueParams(result, params, allowedParams);

        if (!result.getValid()) {
            return;
        }

        validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
        validateStringLength(result, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);

        String login = getFirstParam(params, "login");
        if (login != null && !login.matches("^[a-zA-Z0-9_]+$")) {
            result.addError("Login can only contain letters, numbers and underscores");
        }
    }

    private void validateUserRequest(String method, String uri, ValidationResult result) {
        Map<String, List<String>> params = parseUriParameters(uri);

        Set<String> allowedParams;
        switch (method.toUpperCase()) {
            case "PUT":
                allowedParams = PUT_USER_PARAMS;
                break;
            case "POST":
                allowedParams = POST_USER_PARAMS;
                break;
            case "DELETE":
                allowedParams = DELETE_USER_PARAMS;
                break;
            default:
                result.addError("Invalid method for user endpoint: " + method +
                        ". Allowed: PUT, POST, DELETE");
                return;
        }

        validateNoExtraParams(result, params, allowedParams, method + " user");

        if (!result.getValid()) {
            return;
        }

        switch (method.toUpperCase()) {
            case "POST":
                validateRequiredParam(result, params, "session_token");
                validateRequiredParam(result, params, "login");
                validateRequiredParam(result, params, "password");
                validateRequiredParam(result, params, "permission");
                break;
            case "PUT":
                validateRequiredParam(result, params, "session_token");
                validateRequiredParam(result, params, "login");
                boolean hasOptionalParam = params.containsKey("new_login") ||
                        params.containsKey("password") ||
                        params.containsKey("permission");
                if (!hasOptionalParam) {
                    result.addError("At least one optional parameter must be specified for PUT: " +
                            "new_login, password, or permission");
                }
                break;
            case "DELETE":
                validateRequiredParam(result, params, "session_token");
                validateRequiredParam(result, params, "login");
                break;
        }

        validateSingleValueParams(result, params, allowedParams);

        if (!result.getValid()) {
            return;
        }

        validateStringLength(result, getFirstParam(params, "session_token"), "session_token", 10, MAX_TOKEN_LENGTH);

        String sessionToken = getFirstParam(params, "session_token");
        if (sessionToken != null && !sessionToken.matches("^[a-fA-F0-9-]+$")) {
            result.addError("Session token can only contain letters, numbers and hyphens");
        }

        if (params.containsKey("login")) {
            validateStringLength(result, getFirstParam(params, "login"), "login", 3, MAX_LOGIN_LENGTH);
            String login = getFirstParam(params, "login");
            if (login != null && !login.matches("^[a-zA-Z0-9_]+$")) {
                result.addError("Login can only contain letters, numbers and underscores");
            }
        }

        if (params.containsKey("new_login")) {
            validateStringLength(result, getFirstParam(params, "new_login"), "new_login", 3, MAX_LOGIN_LENGTH);
            String newLogin = getFirstParam(params, "new_login");
            if (newLogin != null && !newLogin.matches("^[a-zA-Z0-9_]+$")) {
                result.addError("New login can only contain letters, numbers and underscores");
            }
        }

        if (params.containsKey("password")) {
            validateStringLength(result, getFirstParam(params, "password"), "password", 6, MAX_PASSWORD_LENGTH);
        }

        if (params.containsKey("permission")) {
            String permission = getFirstParam(params, "permission");
            if (!PermissionType.isValid(permission)) {
                result.addError(
                        "Invalid permission type. Allowed: " + String.join(", ", PermissionType.getAllValues()));
            }
        }
    }

    private void validateConfigRequest(String method, String uri, ValidationResult result) {
        Map<String, List<String>> params = parseUriParameters(uri);

        if (!"PUT".equalsIgnoreCase(method)) {
            result.addError(
                    "Unsupported method for configuration endpoint: " + method + ". Only PUT is allowed");
            return;
        }

        Set<String> allowedParams = PUT_CONFIG_PARAMS;

        validateNoExtraParams(result, params, allowedParams, method + " configuration");

        if (!result.getValid()) {
            return;
        }

        validateRequiredParam(result, params, "session_token");
        validateRequiredParam(result, params, "max_memory_policy");
        validateRequiredParam(result, params, "max_storage_memory");
        validateRequiredParam(result, params, "persistence");

        validateSingleValueParams(result, params, allowedParams);

        if (!result.getValid()) {
            return;
        }

        validateStringLength(result, getFirstParam(params, "session_token"), "session_token", 10, MAX_TOKEN_LENGTH);

        String sessionToken = getFirstParam(params, "session_token");
        if (sessionToken != null && !sessionToken.matches("^[a-fA-F0-9-]+$")) {
            result.addError("Session token can only contain letters, numbers and hyphens");
        }

        String policy = getFirstParam(params, "max_memory_policy");
        if (!MaxMemoryPolicy.isValid(policy)) {
            result.addError(
                    "Invalid max_memory_policy. Allowed: " + String.join(", ", MaxMemoryPolicy.getAllValues()));
        }

        String maxMemory = getFirstParam(params, "max_storage_memory");
        if (maxMemory != null) {
            try {
                long memoryValue = Long.parseLong(maxMemory);
                if (memoryValue <= 0) {
                    result.addError("max_storage_memory must be a positive number");
                }
            } catch (NumberFormatException e) {
                result.addError("max_storage_memory must be a valid number");
            }
        }

        String persistence = getFirstParam(params, "persistence");
        if (persistence != null) {
            try {
                Boolean.parseBoolean(persistence);
            } catch (IllegalArgumentException e) {
                result.addError("persistence must be true or false");
            }
        }
    }

    // Вспомогательные методы

    public Map<String, List<String>> parseUriParameters(String uri) {
        QueryStringDecoder decoder = new QueryStringDecoder(uri);
        return decoder.parameters();
    }

    private Set<String> getAllowedCacheParams(String method, Map<String, List<String>> params) {
        switch (method.toUpperCase()) {
            case "GET":
                if (params.containsKey("sql")) {
                    return GET_CACHE_PARAMS_WITH_SQL;
                }
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

    private boolean isValidTTLFormat(String ttl) {
        if (ttl == null || ttl.trim().isEmpty()) {
            return false;
        }

        String ttlLower = ttl.toLowerCase().trim();

        return ttlLower.matches("^\\d+$") ||
                ttlLower.matches("^\\d+ms$") ||
                ttlLower.matches("^\\d+s$") ||
                ttlLower.matches("^\\d+m$") ||
                ttlLower.matches("^\\d+h$") ||
                ttlLower.matches("^\\d+d$");
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