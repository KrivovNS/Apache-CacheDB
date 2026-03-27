package com.mipt.controller;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.PermissionType;
import com.mipt.model.Session;
import com.mipt.model.MaxMemoryPolicy;
import com.mipt.model.HttpMethod;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.telemetry.TelemetryService;
import com.mipt.database.dao.UserDAO;
import com.mipt.model.User;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class NettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  private static final Logger log = LoggerFactory.getLogger(NettyHandler.class);

  private final CacheStorageService cacheService;
  private final SessionService sessionService;
  private final UserDAO userDAO;
  private final RequestParametersValidator validator;
  private final TelemetryService telemetryService;

  public NettyHandler(CacheStorageService cacheService,
      SessionService sessionService,
      UserDAO userDAO,
      TelemetryService telemetryService) {
    this.cacheService = cacheService;
    this.sessionService = sessionService;
    this.userDAO = userDAO;
    this.validator = new RequestParametersValidator();
    this.telemetryService = telemetryService;
  }

  public NettyHandler(CacheStorageService cacheService,
      SessionService sessionService,
      UserDAO userDAO) {
    this(cacheService, sessionService, userDAO, TelemetryService.disabled(cacheService));
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    String methodStr = request.method().name();
    String uri = request.uri();
    String requestType = resolveHttpRequestType(methodStr, uri);

    log.info("Received {} request: {}", methodStr, uri);

    if (uri.startsWith("/metrics")) {
      sendMetrics(ctx, requestType);
      return;
    }

    if ("/".equals(uri)) {
      sendInfoPage(ctx, requestType);
      return;
    }

    String content = request.content().toString(CharsetUtil.UTF_8);

    ValidationResult validation = validator.validateRequest(methodStr, uri);
    if (!validation.getValid()) {
      sendBadRequest(ctx, validation.getErrors(), requestType);
      return;
    }

    // Конвертируем строку в enum
    HttpMethod method = HttpMethod.fromString(methodStr);
    if (method == null) {
      sendBadRequest(ctx, "Invalid HTTP method: " + methodStr, requestType);
      return;
    }

    // Обработка запросов к кэшу
    if (uri.startsWith("/cache")) {
      handleCacheRequest(ctx, method, uri, content, requestType);
      return;
    }

    if (uri.startsWith("/auth")) {
      handleAuthRequest(ctx, method, uri, content, requestType);
      return;
    }

    if (uri.startsWith("/configuration")) {
      handleConfigRequest(ctx, method, uri, content, requestType);
      return;
    }

    if (uri.startsWith("/user")) {
      handleUserRequest(ctx, method, uri, content, requestType);
      return;
    }

    sendNotFound(ctx, "Endpoint not found: " + uri, requestType);
  }

  private void handleCacheRequest(ChannelHandlerContext ctx, HttpMethod method, String uri,
      String content, String requestType) {
    Map<String, String> params = parseUri(uri);
    String sessionToken = params.get("session_token");
    String key = params.get("key");

    FullHttpResponse response;

    try {
      // Проверяем валидность сессии
      if (!isSessionValid(sessionToken)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      // Проверяем обязательные параметры
      if (sessionToken == null || key == null) {
        response = createResponse(HttpResponseStatus.BAD_REQUEST,
            "Missing required parameters: session_token and key");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      // Получаем сессию для проверки прав доступа
      Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
      if (sessionOpt.isEmpty()) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      Session session = sessionOpt.get();
      PermissionType userPermission = session.getPermissionType();

      // Обработка в зависимости от метода
      if (method.isGet()) {
        response = handleCacheGet(key);
      } else if (method.isWriteMethod()) { // POST или PUT
        if (userPermission == PermissionType.READER) {
          response = createResponse(HttpResponseStatus.FORBIDDEN,
              "READER users can only get data");
          writeHttpResponse(ctx, response, requestType);
          return;
        }
        response = handleCacheWrite(method, sessionToken, key, params, content);
      } else if (method.isDelete()) {
        if (userPermission == PermissionType.READER) {
          response = createResponse(HttpResponseStatus.FORBIDDEN,
              "READER users can only get data");
          writeHttpResponse(ctx, response, requestType);
          return;
        }
        response = handleCacheDelete(key);
      } else {
        response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED,
            "Method " + method.getMethod() + " not allowed for cache endpoint");
      }
    } catch (Exception e) {
      log.error("Error processing cache request", e);
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Server error: " + e.getMessage());
    }

    writeHttpResponse(ctx, response, requestType);
  }

  private FullHttpResponse handleCacheGet(String key) {
    try {
      CacheResult result = cacheService.get(key);

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.NOT_FOUND, result.getMessage());
      }

      return createResponse(HttpResponseStatus.OK, result.getData().toString());

    } catch (Exception e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error reading data: " + e.getMessage());
    }
  }

  private FullHttpResponse handleCacheWrite(HttpMethod method, String sessionToken,
      String key, Map<String, String> params,
      String content) {
    String type = params.get("type");
    if (type == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing required parameter: type");
    }

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data type: " + type + ". Valid types: " +
              String.join(", ", DataType.getAllValues()));
    }

    if (content == null || content.trim().isEmpty()) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Request body is required for " + method.getMethod() + " method");
    }

    // Парсим TTL если он есть
    String ttlParam = params.get("ttl");
    Long ttlSeconds = null;
    if (ttlParam != null) {
      try {
        Long ttlMs = parseTTL(ttlParam);
        if (ttlMs <= 0) {
          return createResponse(HttpResponseStatus.BAD_REQUEST,
              "TTL must be positive");
        }
        ttlSeconds = ttlMs / 1000; // Конвертируем в секунды для cacheService
      } catch (IllegalArgumentException e) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid TTL format: " + e.getMessage());
      }
    }

    try {
      // Получаем пользователя из сессии
      Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
      if (sessionOpt.isEmpty()) {
        return createResponse(HttpResponseStatus.UNAUTHORIZED, "Session expired");
      }

      User user = sessionOpt.get().getCreator();
      String username = user.getUsername();

      // Рассчитываем размер данных
      CacheResult result;
      if (method.isPost()) {
        result = cacheService.post(key, content, dataType, username, ttlSeconds);
      } else { // PUT
        result = cacheService.put(key, content, dataType, username, ttlSeconds);
      }

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
      }

      String responseMessage = result.getMessage();
      if (ttlSeconds != null) {
        responseMessage += " (TTL: " + formatTTL(ttlSeconds * 1000) + ")";
      }

      return createResponse(HttpResponseStatus.OK, responseMessage);

    } catch (Exception e) {
      log.error("Error in cache " + method.getMethod(), e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error processing data: " + e.getMessage());
    }
  }

  private FullHttpResponse handleCacheDelete(String key) {
    try {
      CacheResult result = cacheService.delete(key);

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
      }

      return createResponse(HttpResponseStatus.OK, result.getMessage());

    } catch (Exception e) {
      log.error("Error deleting cache entry", e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error deleting data: " + e.getMessage());
    }
  }

  private void handleAuthRequest(ChannelHandlerContext ctx, HttpMethod method, String uri,
      String content, String requestType) {
    Map<String, String> params = parseUri(uri);
    FullHttpResponse response;

    try {
      // Для /auth поддерживается только GET
      if (method.isGet()) {
        response = handleAuthLogin(params);
      } else {
        response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED,
            "Method " + method.getMethod() + " not allowed for /auth endpoint. Use GET with login and password parameters");
      }
    } catch (Exception e) {
      log.error("Error processing auth request", e);
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Server error: " + e.getMessage());
    }

    writeHttpResponse(ctx, response, requestType);
  }

  private FullHttpResponse handleAuthLogin(Map<String, String> params) {
    String login = params.get("login");
    String password = params.get("password");

    if (login == null || password == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing required parameters: login and password");
    }

    try {
      // Ищем пользователя в базе
      User user = userDAO.findByUsername(login);

      if (user == null) {
        return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid login or password");
      }

      // Проверяем пароль
      if (!user.getPassword().equals(password)) {
        return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid login or password");
      }

      // Создаем сессию для пользователя
      String sessionToken = sessionService.createSessionForUser(user);

      return createResponse(HttpResponseStatus.OK,
          "Authentication successful\n" +
              "Session token: " + sessionToken + "\n" +
              "User: " + user.getUsername() + "\n" +
              "Permission: " + user.getPermissionType().getValue());

    } catch (Exception e) {
      log.error("Error during authentication", e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error during authentication: " + e.getMessage());
    }
  }

  private void handleConfigRequest(ChannelHandlerContext ctx, HttpMethod method, String uri,
      String content, String requestType) {
    Map<String, String> params = parseUri(uri);
    FullHttpResponse response;

    try {
      String sessionToken = params.get("session_token");

      // Проверяем валидность сессии
      if (!isSessionValid(sessionToken)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      // Проверяем сессию
      if (!isSuperAdminSession(sessionToken)) {
        response = createResponse(HttpResponseStatus.FORBIDDEN,
            "Only SUPERADMIN users can modify configuration");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      // Для /configuration поддерживается только PUT
      if (method.isPut()) {
        response = handleUpdateConfiguration(params);
      } else {
        response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED,
            "Method " + method.getMethod() + " not allowed for /configuration endpoint. Use PUT");
      }
    } catch (Exception e) {
      log.error("Error processing config request", e);
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Server error: " + e.getMessage());
    }

    writeHttpResponse(ctx, response, requestType);
  }

  private FullHttpResponse handleUpdateConfiguration(Map<String, String> params) {
    String maxMemoryPolicyParam = params.get("max_memory_policy");
    String maxStorageMemoryParam = params.get("max_storage_memory");
    String persistenceParam = params.get("persistence");

    // Проверяем обязательные параметры
    if (maxMemoryPolicyParam == null || maxStorageMemoryParam == null || persistenceParam == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing required parameters: maxmemory_policy, max_storage_memory and persistence");
    }

    try {
      // Парсим политику памяти
      MaxMemoryPolicy maxMemoryPolicy = MaxMemoryPolicy.fromString(maxMemoryPolicyParam);
      if (maxMemoryPolicy == null) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid max_memory_policy. Valid values: " +
                String.join(", ", MaxMemoryPolicy.getAllValues()));
      }

      // Парсим максимальный размер памяти
      long maxStorageMemory;
      try {
        maxStorageMemory = Long.parseLong(maxStorageMemoryParam);
        if (maxStorageMemory <= 0) {
          return createResponse(HttpResponseStatus.BAD_REQUEST,
              "max_storage_memory must be positive");
        }
      } catch (NumberFormatException e) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid max_storage_memory format. Must be a number");
      }

      // Парсим параметр persistence
      boolean persistence;
      try {
        persistence = Boolean.parseBoolean(persistenceParam);
      } catch (Exception e) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid persistence format. Must be 'true' or 'false'");
      }

      // Изменяем конфигурацию кэш-сервиса
      CacheResult result = cacheService.changePolicy(maxMemoryPolicy, maxStorageMemory, persistence);

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
      }

      return createResponse(HttpResponseStatus.OK,
          "Configuration updated successfully\n" +
              "Max memory policy: " + maxMemoryPolicy.getValue() + "\n" +
              "Max storage memory: " + maxStorageMemory + " bytes\n" +
              "Persistence: " + persistence);

    } catch (Exception e) {
      log.error("Error updating configuration", e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error updating configuration: " + e.getMessage());
    }
  }

  private void handleUserRequest(ChannelHandlerContext ctx, HttpMethod method, String uri,
      String content, String requestType) {
    Map<String, String> params = parseUri(uri);
    FullHttpResponse response;

    try {
      String sessionToken = params.get("session_token");

      // Проверяем валидность сессии
      if (!isSessionValid(sessionToken)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      // Проверяем сессию для всех операций с пользователями
      if (!isSuperAdminSession(sessionToken)) {
        response = createResponse(HttpResponseStatus.FORBIDDEN,
            "Only SUPERADMIN users can manage users");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      if (method.isPut()) {
        response = handleUpdateUser(params);
      } else if (method.isPost()) {
        response = handleCreateUser(params);
      } else if (method.isDelete()) {
        response = handleDeleteUser(params);
      } else {
        response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED,
            "Method " + method.getMethod() + " not allowed for /user endpoint");
      }
    } catch (Exception e) {
      log.error("Error processing user request", e);
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Server error: " + e.getMessage());
    }

    writeHttpResponse(ctx, response, requestType);
  }

  private FullHttpResponse handleCreateUser(Map<String, String> params) {
    String newLogin = params.get("login");
    String password = params.get("password");
    String permissionParam = params.get("permission");

    if (newLogin == null || password == null || permissionParam == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing required parameters: new_login, password and permission");
    }

    try {
      // Проверяем формат permission
      PermissionType permissionType = PermissionType.fromString(permissionParam);
      if (permissionType == null) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid permission. Valid values: " +
                String.join(", ", PermissionType.getAllValues()));
      }

      // Проверяем, не существует ли уже пользователь с таким логином
      User existingUser = userDAO.findByUsername(newLogin);
      if (existingUser != null) {
        return createResponse(HttpResponseStatus.CONFLICT,
            "User with login '" + newLogin + "' already exists");
      }

      // Создаем нового пользователя
      User newUser = userDAO.createUser(newLogin, password, permissionType);

      if (newUser == null) {
        return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
            "Failed to create user");
      }

      return createResponse(HttpResponseStatus.OK,
          "User created successfully\n" +
              "ID: " + newUser.getId() + "\n" +
              "Login: " + newUser.getUsername() + "\n" +
              "Permission: " + newUser.getPermissionType().getValue());

    } catch (Exception e) {
      log.error("Error creating user", e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error creating user: " + e.getMessage());
    }
  }

  private FullHttpResponse handleUpdateUser(Map<String, String> params) {
    String login = params.get("login");
    String newLogin = params.get("new_login");
    String password = params.get("password");
    String permissionParam = params.get("permission");

    if (login == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing required parameter: login");
    }

    // Хотя бы один параметр для обновления должен быть указан
    if (newLogin == null && password == null && permissionParam == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "At least one update parameter must be specified: new_login, password or permission");
    }

    try {
      // Находим существующего пользователя
      User existingUser = userDAO.findByUsername(login);
      if (existingUser == null) {
        return createResponse(HttpResponseStatus.NOT_FOUND,
            "User with login '" + login + "' not found");
      }

      sessionService.removeSessionIfExists(existingUser);

      // Парсим permission если указан
      PermissionType permissionType = null;
      if (permissionParam != null) {
        permissionType = PermissionType.fromString(permissionParam);
        if (permissionType == null) {
          return createResponse(HttpResponseStatus.BAD_REQUEST,
              "Invalid permission. Valid values: " +
                  String.join(", ", PermissionType.getAllValues()));
        }
      }

      // Обновляем пользователя в базе данных
      boolean success = userDAO.updateUser(login, newLogin, password, permissionType);

      if (!success) {
        return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
            "Failed to update user");
      }

      // Получаем обновленного пользователя
      User updatedUser = userDAO.findByUsername(newLogin != null ? newLogin : login);

      return createResponse(HttpResponseStatus.OK,
          "User updated successfully\n" +
              "ID: " + updatedUser.getId() + "\n" +
              "Login: " + updatedUser.getUsername() + "\n" +
              "Permission: " + updatedUser.getPermissionType().getValue());

    } catch (Exception e) {
      log.error("Error updating user", e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error updating user: " + e.getMessage());
    }
  }

  private FullHttpResponse handleDeleteUser(Map<String, String> params) {
    String login = params.get("login");

    if (login == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing required parameter: login");
    }

    try {
      // Находим пользователя
      User existingUser = userDAO.findByUsername(login);
      if (existingUser == null) {
        return createResponse(HttpResponseStatus.NOT_FOUND,
            "User with login '" + login + "' not found");
      }

      // Удаляем пользователя из базы данных
      boolean success = userDAO.deleteUser(login);

      if (!success) {
        return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
            "Failed to delete user");
      }

      sessionService.removeSessionIfExists(existingUser);

      return createResponse(HttpResponseStatus.OK,
          "User deleted successfully\n" +
              "Deleted user: " + login);

    } catch (Exception e) {
      log.error("Error deleting user", e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error deleting user: " + e.getMessage());
    }
  }

  // Вспомогательные методы

  /**
   * Проверяет валидность сессии
   */
  private boolean isSessionValid(String sessionToken) {
    if (sessionToken == null) {
      return false;
    }
    return sessionService.getValidSession(sessionToken).isPresent();
  }

  /**
   * Проверяет, является ли сессия сессией SUPERADMIN
   */
  private boolean isSuperAdminSession(String sessionToken) {
    if (!isSessionValid(sessionToken)) {
      return false;
    }

    Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
    if (sessionOpt.isEmpty()) {
      return false;
    }

    Session session = sessionOpt.get();
    return session.getPermissionType() == PermissionType.SUPERADMIN;
  }

  /**
   * Парсит строку TTL в миллисекунды
   */
  private Long parseTTL(String ttlString) throws IllegalArgumentException {
    if (ttlString == null || ttlString.trim().isEmpty()) {
      return null;
    }

    ttlString = ttlString.trim().toLowerCase();

    try {
      if (ttlString.endsWith("ms")) {
        String value = ttlString.substring(0, ttlString.length() - 2);
        long ttl = Long.parseLong(value);
        if (ttl <= 0) throw new IllegalArgumentException("TTL must be positive");
        return ttl;
      } else if (ttlString.endsWith("s")) {
        String value = ttlString.substring(0, ttlString.length() - 1);
        long ttl = Long.parseLong(value);
        if (ttl <= 0) throw new IllegalArgumentException("TTL must be positive");
        return ttl * 1000;
      } else if (ttlString.endsWith("m")) {
        String value = ttlString.substring(0, ttlString.length() - 1);
        long ttl = Long.parseLong(value);
        if (ttl <= 0) throw new IllegalArgumentException("TTL must be positive");
        return ttl * 60 * 1000;
      } else if (ttlString.endsWith("h")) {
        String value = ttlString.substring(0, ttlString.length() - 1);
        long ttl = Long.parseLong(value);
        if (ttl <= 0) throw new IllegalArgumentException("TTL must be positive");
        return ttl * 60 * 60 * 1000;
      } else if (ttlString.endsWith("d")) {
        String value = ttlString.substring(0, ttlString.length() - 1);
        long ttl = Long.parseLong(value);
        if (ttl <= 0) throw new IllegalArgumentException("TTL must be positive");
        return ttl * 24 * 60 * 60 * 1000;
      } else {
        long ttl = Long.parseLong(ttlString);
        if (ttl <= 0) throw new IllegalArgumentException("TTL must be positive");
        return ttl;
      }
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid TTL format: " + ttlString +
          ". Expected format: <number>[ms|s|m|h|d] or plain milliseconds");
    }
  }

  /**
   * Форматирует TTL в удобочитаемую строку
   */
  private String formatTTL(long ttlMs) {
    if (ttlMs < 1000) {
      return ttlMs + "ms";
    } else if (ttlMs < 60 * 1000) {
      return (ttlMs / 1000) + "s";
    } else if (ttlMs < 60 * 60 * 1000) {
      return (ttlMs / (60 * 1000)) + "m";
    } else if (ttlMs < 24 * 60 * 60 * 1000) {
      return (ttlMs / (60 * 60 * 1000)) + "h";
    } else {
      return (ttlMs / (24 * 60 * 60 * 1000)) + "d";
    }
  }

  private void sendInfoPage(ChannelHandlerContext ctx, String requestType) {
    String info = "Cache Storage HTTP Server\n\n" +
        "Available endpoints:\n\n" +
        "AUTHENTICATION:\n" +
        "GET    /auth?login=USERNAME&password=PASSWORD -> returns session token\n\n" +
        "CACHE OPERATIONS:\n" +
        "GET    /cache?session_token=TOKEN&key=KEY\n" +
        "POST   /cache?session_token=TOKEN&key=KEY&type=TYPE&ttl=TTL (with body - insert)\n" +
        "PUT    /cache?session_token=TOKEN&key=KEY&type=TYPE&ttl=TTL (with body - update)\n" +
        "DELETE /cache?session_token=TOKEN&key=KEY\n\n" +
        "Data types: " + String.join(", ", DataType.getAllValues()) + "\n" +
        "TTL format: <number>[ms|s|m|h|d] or plain milliseconds\n\n" +
        "USER MANAGEMENT (SUPERADMIN only):\n" +
        "POST    /user?session_token=TOKEN&new_login=LOGIN&password=PASSWORD&permission=PERMISSION (create user)\n" +
        "PUT   /user?session_token=TOKEN&login=LOGIN&[new_login=LOGIN]&[password=PASSWORD]&[permission=PERMISSION] (update user)\n" +
        "DELETE /user?session_token=TOKEN&login=LOGIN (delete user)\n\n" +
        "Permissions: " + String.join(", ", PermissionType.getAllValues()) + "\n\n" +
        "CONFIGURATION (SUPERADMIN only):\n" +
        "PUT    /configuration?session_token=TOKEN&maxmemory_policy=POLICY&max_storage_memory=SIZE\n\n" +
        "Max memory policies: " + String.join(", ", MaxMemoryPolicy.getAllValues()) + "\n\n" +
        "Examples:\n" +
        "  Auth:         curl \"http://localhost:8080/auth?login=default&password=admin\"\n" +
        "  Create user:  curl -X PUT \"http://localhost:8080/user?session_token=XYZ&new_login=bob&password=123&permission=admin\"\n" +
        "  Set cache:    curl -X POST -d 'data' \"http://localhost:8080/cache?session_token=XYZ&key=test&type=string&ttl=30s\"\n\n" +
        "== TCP API (port 9090) ==\n\n" +
        "Connect: telnet localhost 9090\n\n" +
        "COMMANDS:\n" +
        "AUTH <login> <password>    -> authenticate\n" +
        "GET <key>    -> get value\n" +
        "SET <key> <type> <value>   -> set value\n" +
        "DELETE <key>   -> delete value\n\n" +
        "  TCP connect:  telnet localhost 9090\n" +
        "  TCP auth:     AUTH default admin123\n" +
        "  TCP set:      SET mykey string hello\n" +
        "  TCP get:      GET mykey";

    FullHttpResponse response = createResponse(HttpResponseStatus.OK, info);
    writeHttpResponse(ctx, response, requestType);
  }

  private void sendBadRequest(ChannelHandlerContext ctx, String message, String requestType) {
    FullHttpResponse response = createResponse(HttpResponseStatus.BAD_REQUEST, message);
    writeHttpResponse(ctx, response, requestType);
  }

  private void sendNotFound(ChannelHandlerContext ctx, String message, String requestType) {
    FullHttpResponse response = createResponse(HttpResponseStatus.NOT_FOUND, message);
    writeHttpResponse(ctx, response, requestType);
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

  private String resolveHttpRequestType(String method, String uri) {
    String normalizedMethod = method == null ? "unknown" : method.toLowerCase(Locale.ROOT);
    String path = uri;
    int queryStart = uri.indexOf('?');
    if (queryStart >= 0) {
      path = uri.substring(0, queryStart);
    }

    if ("/".equals(path)) {
      return "http.root." + normalizedMethod;
    }
    if (path.startsWith("/cache")) {
      return "http.cache." + normalizedMethod;
    }
    if (path.startsWith("/auth")) {
      return "http.auth." + normalizedMethod;
    }
    if (path.startsWith("/configuration")) {
      return "http.configuration." + normalizedMethod;
    }
    if (path.startsWith("/user")) {
      return "http.user." + normalizedMethod;
    }
    if (path.startsWith("/metrics")) {
      return "http.metrics." + normalizedMethod;
    }

    return "http.unknown." + normalizedMethod;
  }

  private FullHttpResponse createResponse(HttpResponseStatus status, String content) {
    return createResponse(status, content, "text/plain; charset=UTF-8");
  }

  private FullHttpResponse createResponse(HttpResponseStatus status, String content, String contentType) {
    ByteBuf buffer = Unpooled.copiedBuffer(content, StandardCharsets.UTF_8);
    FullHttpResponse response = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, status, buffer
    );

    response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

    return response;
  }

  private void sendMetrics(ChannelHandlerContext ctx, String requestType) {
    FullHttpResponse response = createResponse(
        HttpResponseStatus.OK,
        telemetryService.scrapePrometheus(),
        "text/plain; version=0.0.4; charset=UTF-8"
    );
    writeHttpResponse(ctx, response, requestType);
  }

  private void writeHttpResponse(ChannelHandlerContext ctx, FullHttpResponse response,
      String requestType) {
    telemetryService.recordHttpRequest(requestType, response.status().code());
    ctx.writeAndFlush(response);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("Error processing request", cause);

    FullHttpResponse errorResponse = createResponse(
        HttpResponseStatus.INTERNAL_SERVER_ERROR,
        "Server error: " + cause.getMessage()
    );
    writeHttpResponse(ctx, errorResponse, "http.exception");
  }
}
