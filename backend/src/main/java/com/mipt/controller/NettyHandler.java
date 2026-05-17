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
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class NettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  private static final Logger log = LoggerFactory.getLogger(NettyHandler.class);

  private final CacheStorageService cacheService;
  private final SessionService sessionService;
  private final UserDAO userDAO;
  private final RequestParametersValidator validator;
  private final TelemetryService telemetryService;

  // Хранилища для структур данных
  private final Map<String, List<String>> listStorage = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> hashStorage = new ConcurrentHashMap<>();

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

    HttpMethod method = HttpMethod.fromString(methodStr);
    if (method == null) {
      sendBadRequest(ctx, "Invalid HTTP method: " + methodStr, requestType);
      return;
    }

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
    String cmd = params.get("cmd");

    FullHttpResponse response;

    try {
      if (!isSessionValid(sessionToken)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      if (sessionToken == null) {
        response = createResponse(HttpResponseStatus.BAD_REQUEST, "Missing required parameter: session_token");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
      if (sessionOpt.isEmpty()) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      Session session = sessionOpt.get();
      PermissionType userPermission = session.getPermissionType();

      // Обработка команд для структур данных (LIST, HASH)
      if (cmd != null && !cmd.isEmpty()) {
        response = handleDataStructureCommand(method, sessionToken, key, cmd, params, content, userPermission);
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      // Обычные кэш-операции
      if (key == null) {
        response = createResponse(HttpResponseStatus.BAD_REQUEST, "Missing required parameter: key");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      if (method.isGet()) {
        response = handleCacheGet(key);
      } else if (method.isWriteMethod()) {
        if (userPermission == PermissionType.READER) {
          response = createResponse(HttpResponseStatus.FORBIDDEN, "READER users can only get data");
          writeHttpResponse(ctx, response, requestType);
          return;
        }
        response = handleCacheWrite(method, sessionToken, key, params, content);
      } else if (method.isDelete()) {
        if (userPermission == PermissionType.READER) {
          response = createResponse(HttpResponseStatus.FORBIDDEN, "READER users can only get data");
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

  /**
   * Обрабатывает команды для структур данных (LIST, HASH)
   */
  private FullHttpResponse handleDataStructureCommand(HttpMethod method, String sessionToken,
                                                      String key, String cmd, Map<String, String> params, String content, PermissionType userPermission) {

    // Команды чтения
    boolean isReadCommand = cmd.equalsIgnoreCase("lrange") || cmd.equalsIgnoreCase("llen") ||
        cmd.equalsIgnoreCase("lpop") || cmd.equalsIgnoreCase("rpop") ||
        cmd.equalsIgnoreCase("hget") || cmd.equalsIgnoreCase("hgetall");

    // Команды записи
    boolean isWriteCommand = cmd.equalsIgnoreCase("lpush") || cmd.equalsIgnoreCase("rpush") ||
        cmd.equalsIgnoreCase("hset") || cmd.equalsIgnoreCase("hdel") || cmd.equalsIgnoreCase("hincrby");

    if (isWriteCommand && userPermission == PermissionType.READER) {
      return createResponse(HttpResponseStatus.FORBIDDEN, "READER users cannot modify data");
    }

    if (key == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "key parameter is required");
    }

    try {
      switch (cmd.toLowerCase()) {
        // ========== LIST Operations ==========
        case "lpush":
          return handleListPush(key, params, content, true);
        case "rpush":
          return handleListPush(key, params, content, false);
        case "lpop":
          return handleListPop(key, true);
        case "rpop":
          return handleListPop(key, false);
        case "lrange":
          return handleListRange(key, params);
        case "llen":
          return handleListLen(key);

        // ========== HASH Operations ==========
        case "hset":
          return handleHashSet(key, params);
        case "hget":
          return handleHashGet(key, params);
        case "hdel":
          return handleHashDel(key, params);
        case "hgetall":
          return handleHashGetAll(key);
        case "hincrby":
          return handleHashIncrBy(key, params);

        default:
          return createResponse(HttpResponseStatus.BAD_REQUEST, "Unknown command: " + cmd);
      }
    } catch (Exception e) {
      log.error("Error executing command: {}", cmd, e);
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Command error: " + e.getMessage());
    }
  }

  // ==================== LIST Implementation ====================

  private FullHttpResponse handleListPush(String key, Map<String, String> params, String content, boolean left) {
    String value = params.get("value");
    if (value == null && content != null && !content.isEmpty()) {
      value = content;
    }
    if (value == null || value.trim().isEmpty()) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Value is required for " + (left ? "LPUSH" : "RPUSH"));
    }

    try {
      List<String> list = listStorage.computeIfAbsent(key, k -> new ArrayList<>());
      if (left) {
        list.add(0, value);
      } else {
        list.add(value);
      }
      return createResponse(HttpResponseStatus.OK, "OK");
    } catch (Exception e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "List push error: " + e.getMessage());
    }
  }

  private FullHttpResponse handleListPop(String key, boolean left) {
    List<String> list = listStorage.get(key);
    if (list == null || list.isEmpty()) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Key not found or not a list");
    }

    String value = left ? list.remove(0) : list.remove(list.size() - 1);

    if (list.isEmpty()) {
      listStorage.remove(key);
    }

    return createResponse(HttpResponseStatus.OK, value);
  }

  private FullHttpResponse handleListRange(String key, Map<String, String> params) {
    String startStr = params.get("start");
    String stopStr = params.get("stop");

    if (startStr == null || stopStr == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "start and stop parameters are required for LRANGE");
    }

    List<String> list = listStorage.get(key);
    if (list == null || list.isEmpty()) {
      return createResponse(HttpResponseStatus.OK, "[]");
    }

    try {
      int start = Integer.parseInt(startStr);
      int stop = Integer.parseInt(stopStr);
      int size = list.size();

      int from = start >= 0 ? start : Math.max(0, size + start);
      int to = stop >= 0 ? Math.min(size - 1, stop) : size + stop;

      if (from > to || from >= size) {
        return createResponse(HttpResponseStatus.OK, "[]");
      }

      List<String> result = list.subList(from, to + 1);
      return createResponse(HttpResponseStatus.OK, result.toString());
    } catch (NumberFormatException e) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "start and stop must be integers");
    }
  }

  private FullHttpResponse handleListLen(String key) {
    List<String> list = listStorage.get(key);
    if (list == null) {
      return createResponse(HttpResponseStatus.OK, "0");
    }
    return createResponse(HttpResponseStatus.OK, String.valueOf(list.size()));
  }

  // ==================== HASH Implementation ====================

  private FullHttpResponse handleHashSet(String key, Map<String, String> params) {
    String field = params.get("field");
    String value = params.get("value");

    if (field == null || value == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "field and value parameters are required for HSET");
    }

    Map<String, String> hash = hashStorage.computeIfAbsent(key, k -> new HashMap<>());
    hash.put(field, value);
    return createResponse(HttpResponseStatus.OK, "OK");
  }

  private FullHttpResponse handleHashGet(String key, Map<String, String> params) {
    String field = params.get("field");
    if (field == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "field parameter is required for HGET");
    }

    Map<String, String> hash = hashStorage.get(key);
    if (hash == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Key not found");
    }

    String value = hash.get(field);
    if (value == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Field not found");
    }

    return createResponse(HttpResponseStatus.OK, value);
  }

  private FullHttpResponse handleHashDel(String key, Map<String, String> params) {
    String field = params.get("field");
    if (field == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "field parameter is required for HDEL");
    }

    Map<String, String> hash = hashStorage.get(key);
    if (hash == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Key not found");
    }

    String removed = hash.remove(field);
    if (removed == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Field not found");
    }

    if (hash.isEmpty()) {
      hashStorage.remove(key);
    }

    return createResponse(HttpResponseStatus.OK, "OK");
  }

  private FullHttpResponse handleHashGetAll(String key) {
    Map<String, String> hash = hashStorage.get(key);
    if (hash == null || hash.isEmpty()) {
      return createResponse(HttpResponseStatus.OK, "{}");
    }
    return createResponse(HttpResponseStatus.OK, hash.toString());
  }

  private FullHttpResponse handleHashIncrBy(String key, Map<String, String> params) {
    String field = params.get("field");
    String incrementStr = params.get("increment");

    if (field == null || incrementStr == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "field and increment parameters are required for HINCRBY");
    }

    try {
      long increment = Long.parseLong(incrementStr);
      Map<String, String> hash = hashStorage.computeIfAbsent(key, k -> new HashMap<>());

      String currentStr = hash.get(field);
      long current = 0;
      if (currentStr != null) {
        try {
          current = Long.parseLong(currentStr);
        } catch (NumberFormatException e) {
          return createResponse(HttpResponseStatus.BAD_REQUEST, "Hash value is not an integer");
        }
      }

      long newValue = current + increment;
      hash.put(field, String.valueOf(newValue));
      return createResponse(HttpResponseStatus.OK, String.valueOf(newValue));
    } catch (NumberFormatException e) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "increment must be a number");
    }
  }

  private FullHttpResponse handleCacheGet(String key) {
    try {
      // Сначала проверяем в LIST хранилище
      if (listStorage.containsKey(key)) {
        return createResponse(HttpResponseStatus.OK, listStorage.get(key).toString());
      }

      // Проверяем в HASH хранилище
      if (hashStorage.containsKey(key)) {
        return createResponse(HttpResponseStatus.OK, hashStorage.get(key).toString());
      }

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

    String ttlParam = params.get("ttl");
    Long ttlSeconds = null;
    if (ttlParam != null) {
      try {
        Long ttlMs = parseTTL(ttlParam);
        if (ttlMs <= 0) {
          return createResponse(HttpResponseStatus.BAD_REQUEST,
              "TTL must be positive");
        }
        ttlSeconds = ttlMs / 1000;
      } catch (IllegalArgumentException e) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid TTL format: " + e.getMessage());
      }
    }

    try {
      Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
      if (sessionOpt.isEmpty()) {
        return createResponse(HttpResponseStatus.UNAUTHORIZED, "Session expired");
      }

      User user = sessionOpt.get().getCreator();
      String username = user.getUsername();

      CacheResult result;
      if (method.isPost()) {
        result = cacheService.post(key, content, dataType, username, ttlSeconds);
      } else {
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
      // Удаляем из LIST хранилища
      if (listStorage.containsKey(key)) {
        listStorage.remove(key);
        return createResponse(HttpResponseStatus.OK, "Deleted");
      }

      // Удаляем из HASH хранилища
      if (hashStorage.containsKey(key)) {
        hashStorage.remove(key);
        return createResponse(HttpResponseStatus.OK, "Deleted");
      }

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
      User user = userDAO.findByUsername(login);

      if (user == null) {
        return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid login or password");
      }

      if (!user.getPassword().equals(password)) {
        return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid login or password");
      }

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

      if (!isSessionValid(sessionToken)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

      if (!isSuperAdminSession(sessionToken)) {
        response = createResponse(HttpResponseStatus.FORBIDDEN,
            "Only SUPERADMIN users can modify configuration");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

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

    if (maxMemoryPolicyParam == null || maxStorageMemoryParam == null || persistenceParam == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing required parameters: max_memory_policy, max_storage_memory and persistence");
    }

    try {
      MaxMemoryPolicy maxMemoryPolicy = MaxMemoryPolicy.fromString(maxMemoryPolicyParam);
      if (maxMemoryPolicy == null) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid max_memory_policy. Valid values: " +
                String.join(", ", MaxMemoryPolicy.getAllValues()));
      }

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

      boolean persistence;
      try {
        persistence = Boolean.parseBoolean(persistenceParam);
      } catch (Exception e) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid persistence format. Must be 'true' or 'false'");
      }

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

      if (!isSessionValid(sessionToken)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        writeHttpResponse(ctx, response, requestType);
        return;
      }

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
          "Missing required parameters: login, password and permission");
    }

    try {
      PermissionType permissionType = PermissionType.fromString(permissionParam);
      if (permissionType == null) {
        return createResponse(HttpResponseStatus.BAD_REQUEST,
            "Invalid permission. Valid values: " +
                String.join(", ", PermissionType.getAllValues()));
      }

      User existingUser = userDAO.findByUsername(newLogin);
      if (existingUser != null) {
        return createResponse(HttpResponseStatus.CONFLICT,
            "User with login '" + newLogin + "' already exists");
      }

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

    if (newLogin == null && password == null && permissionParam == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "At least one update parameter must be specified: new_login, password or permission");
    }

    try {
      User existingUser = userDAO.findByUsername(login);
      if (existingUser == null) {
        return createResponse(HttpResponseStatus.NOT_FOUND,
            "User with login '" + login + "' not found");
      }

      sessionService.removeSessionIfExists(existingUser);

      PermissionType permissionType = null;
      if (permissionParam != null) {
        permissionType = PermissionType.fromString(permissionParam);
        if (permissionType == null) {
          return createResponse(HttpResponseStatus.BAD_REQUEST,
              "Invalid permission. Valid values: " +
                  String.join(", ", PermissionType.getAllValues()));
        }
      }

      boolean success = userDAO.updateUser(login, newLogin, password, permissionType);

      if (!success) {
        return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
            "Failed to update user");
      }

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
      User existingUser = userDAO.findByUsername(login);
      if (existingUser == null) {
        return createResponse(HttpResponseStatus.NOT_FOUND,
            "User with login '" + login + "' not found");
      }

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

  private boolean isSessionValid(String sessionToken) {
    if (sessionToken == null) {
      return false;
    }
    return sessionService.getValidSession(sessionToken).isPresent();
  }

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
        "LIST OPERATIONS:\n" +
        "GET    /cache?session_token=TOKEN&cmd=lrange&key=KEY&start=0&stop=-1\n" +
        "GET    /cache?session_token=TOKEN&cmd=llen&key=KEY\n" +
        "POST   /cache?session_token=TOKEN&cmd=lpush&key=KEY&value=VALUE\n" +
        "POST   /cache?session_token=TOKEN&cmd=rpush&key=KEY&value=VALUE\n" +
        "GET    /cache?session_token=TOKEN&cmd=lpop&key=KEY\n" +
        "GET    /cache?session_token=TOKEN&cmd=rpop&key=KEY\n\n" +
        "HASH OPERATIONS:\n" +
        "POST   /cache?session_token=TOKEN&cmd=hset&key=KEY&field=FIELD&value=VALUE\n" +
        "GET    /cache?session_token=TOKEN&cmd=hget&key=KEY&field=FIELD\n" +
        "DELETE /cache?session_token=TOKEN&cmd=hdel&key=KEY&field=FIELD\n" +
        "GET    /cache?session_token=TOKEN&cmd=hgetall&key=KEY\n" +
        "POST   /cache?session_token=TOKEN&cmd=hincrby&key=KEY&field=FIELD&increment=5\n\n" +
        "Data types: " + String.join(", ", DataType.getAllValues()) + "\n" +
        "TTL format: <number>[ms|s|m|h|d] or plain milliseconds\n\n" +
        "USER MANAGEMENT (SUPERADMIN only):\n" +
        "POST    /user?session_token=TOKEN&login=LOGIN&password=PASSWORD&permission=PERMISSION (create user)\n" +
        "PUT   /user?session_token=TOKEN&login=LOGIN&[new_login=LOGIN]&[password=PASSWORD]&[permission=PERMISSION] (update user)\n" +
        "DELETE /user?session_token=TOKEN&login=LOGIN (delete user)\n\n" +
        "Permissions: " + String.join(", ", PermissionType.getAllValues()) + "\n\n" +
        "CONFIGURATION (SUPERADMIN only):\n" +
        "PUT    /configuration?session_token=TOKEN&max_memory_policy=POLICY&max_storage_memory=SIZE&persistence=true/false\n\n" +
        "Max memory policies: " + String.join(", ", MaxMemoryPolicy.getAllValues()) + "\n\n" +
        "Examples:\n" +
        "  Auth:              curl \"http://localhost:8080/auth?login=default&password=admin123\"\n" +
        "  Get cache:         curl \"http://localhost:8080/cache?session_token=TOKEN&key=test\"\n" +
        "  Set cache:         curl -X POST -d 'data' \"http://localhost:8080/cache?session_token=TOKEN&key=test&type=string&ttl=30s\"\n" +
        "  LPUSH list:        curl -X POST \"http://localhost:8080/cache?session_token=TOKEN&cmd=lpush&key=mylist&value=hello\"\n" +
        "  LRANGE list:       curl \"http://localhost:8080/cache?session_token=TOKEN&cmd=lrange&key=mylist&start=0&stop=-1\"\n" +
        "  HSET hash:         curl -X POST \"http://localhost:8080/cache?session_token=TOKEN&cmd=hset&key=myhash&field=name&value=John\"\n" +
        "  HGET hash:         curl \"http://localhost:8080/cache?session_token=TOKEN&cmd=hget&key=myhash&field=name\"\n\n" +
        "== TCP API (port 9090) ==\n\n" +
        "Connect: telnet localhost 9090\n\n" +
        "COMMANDS:\n" +
        "AUTH <login> <password>    -> authenticate\n" +
        "GET <key>                  -> get value\n" +
        "SET <key> <type> <value>   -> set value\n" +
        "DELETE <key>               -> delete value\n" +
        "LPUSH <key> <value>        -> left push to list\n" +
        "RPUSH <key> <value>        -> right push to list\n" +
        "LRANGE <key> <start> <stop> -> get list range\n" +
        "LLEN <key>                 -> get list length\n" +
        "LPOP <key>                 -> left pop from list\n" +
        "RPOP <key>                 -> right pop from list\n" +
        "HSET <key> <field> <value> -> set hash field\n" +
        "HGET <key> <field>         -> get hash field\n" +
        "HDEL <key> <field>         -> delete hash field\n" +
        "HGETALL <key>              -> get all hash fields\n" +
        "HINCRBY <key> <field> <inc> -> increment hash field\n\n" +
        "TCP examples:\n" +
        "  AUTH default admin123\n" +
        "  LPUSH mylist hello\n" +
        "  LRANGE mylist 0 -1\n" +
        "  HSET myhash name John\n" +
        "  HGET myhash name";

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
        String[] keyValue = pair.split("=", 2);
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