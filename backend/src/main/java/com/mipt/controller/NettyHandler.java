package com.mipt.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.PermissionType;
import com.mipt.model.Session;
import com.mipt.model.MaxMemoryPolicy;
import com.mipt.model.HttpMethod;
import com.mipt.model.BatchCommand;
import com.mipt.model.BatchResult;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.service.BatchCommandService;
import com.mipt.service.RateLimitService;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;

    public NettyHandler(CacheStorageService cacheService,
            SessionService sessionService,
            UserDAO userDAO,
            TelemetryService telemetryService,
            RateLimitService rateLimitService) {
        this.cacheService = cacheService;
        this.sessionService = sessionService;
        this.userDAO = userDAO;
        this.validator = new RequestParametersValidator();
        this.telemetryService = telemetryService;
        this.objectMapper = new ObjectMapper();
        this.rateLimitService = rateLimitService;
    }

    public NettyHandler(CacheStorageService cacheService,
            SessionService sessionService,
            UserDAO userDAO) {
        this(cacheService, sessionService, userDAO,
                TelemetryService.disabled(cacheService),
                new RateLimitService(sessionService, false));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String methodStr = request.method().name();
        String uri = request.uri();
        String requestType = resolveHttpRequestType(methodStr, uri);

        log.info("Received {} request: {}", methodStr, uri);

        // ============ RATE LIMIT CHECK ============
        String rateLimitKey = getRateLimitKey(ctx, uri);

        if (uri.startsWith("/auth")) {
            if (!rateLimitService.allowRequest(rateLimitKey)) {
                sendTooManyRequests(ctx, requestType);
                return;
            }
        } else if (!uri.startsWith("/metrics") && !"/".equals(uri) && !uri.startsWith("/ratelimit-stats")) {
            String sessionToken = extractSessionToken(uri);
            if (!rateLimitService.allowRequest(rateLimitKey, sessionToken)) {
                sendTooManyRequests(ctx, requestType);
                return;
            }
        }

        // ============ METRICS ============
        if (uri.startsWith("/metrics")) {
            sendMetrics(ctx, requestType);
            return;
        }

        // ============ INFO PAGE ============
        if ("/".equals(uri)) {
            sendInfoPage(ctx, requestType);
            return;
        }
        // ============ SHOW CONFIGURATION============
        if (uri.startsWith("/configuration/show")) {
            handleShowConfiguration(ctx, requestType);
            return;
        }

        // ============ RATE LIMIT STATS ============
        if (uri.startsWith("/ratelimit-stats")) {
            sendRateLimitStats(ctx, requestType);
            return;
        }

        String content = request.content().toString(CharsetUtil.UTF_8);

        // ============ PIPELINE ============
        if (uri.startsWith("/pipeline")) {
            handlePipelineRequest(ctx, methodStr, content, requestType);
            return;
        }

        // ============ VALIDATION ============
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

        // ============ ROUTING ============
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

    // ============ PIPELINE HANDLER ============

    private void handlePipelineRequest(ChannelHandlerContext ctx, String methodStr,
            String content, String requestType) {
        FullHttpResponse response;

        try {
            if (!"POST".equalsIgnoreCase(methodStr)) {
                response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED,
                        "{\"success\":false,\"message\":\"Only POST method is allowed for /pipeline endpoint\"}",
                        "application/json; charset=UTF-8");
                writeHttpResponse(ctx, response, requestType);
                return;
            }

            if (content == null || content.trim().isEmpty()) {
                response = createResponse(HttpResponseStatus.BAD_REQUEST,
                        "{\"success\":false,\"message\":\"Request body is required for pipeline\"}",
                        "application/json; charset=UTF-8");
                writeHttpResponse(ctx, response, requestType);
                return;
            }

            List<BatchCommand> commands;
            try {
                commands = objectMapper.readValue(content, new TypeReference<List<BatchCommand>>() {});
            } catch (Exception e) {
                response = createResponse(HttpResponseStatus.BAD_REQUEST,
                        "{\"success\":false,\"message\":\"Invalid JSON format: " + e.getMessage() + "\"}",
                        "application/json; charset=UTF-8");
                writeHttpResponse(ctx, response, requestType);
                return;
            }

            if (commands == null || commands.isEmpty()) {
                response = createResponse(HttpResponseStatus.BAD_REQUEST,
                        "{\"success\":false,\"message\":\"Pipeline request must contain at least one command\"}",
                        "application/json; charset=UTF-8");
                writeHttpResponse(ctx, response, requestType);
                return;
            }

            String rateLimitKey = getRateLimitKey(ctx, "/pipeline");
            if (!rateLimitService.allowBatchRequest(rateLimitKey, commands.size())) {
                sendTooManyRequests(ctx, requestType);
                return;
            }

            BatchCommandService batchService = new BatchCommandService(
                    cacheService, sessionService, userDAO);

            BatchResult batchResult = batchService.executeBatch(commands);

            String jsonResponse = objectMapper.writeValueAsString(batchResult);

            HttpResponseStatus status = batchResult.isSuccess()
                    ? HttpResponseStatus.OK
                    : HttpResponseStatus.BAD_REQUEST;

            response = createResponse(status, jsonResponse, "application/json; charset=UTF-8");

        } catch (Exception e) {
            log.error("Error processing pipeline request", e);
            response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "{\"success\":false,\"message\":\"Server error: " + e.getMessage() + "\"}",
                    "application/json; charset=UTF-8");
        }

        writeHttpResponse(ctx, response, requestType);
    }

    // ============ SQL HANDLER METHODS ============

    private void handleSqlQuery(ChannelHandlerContext ctx, String sql, Session session, PermissionType userPermission) {
        log.info("SQL query: {}", sql);

        SqlParser.ParsedQuery parsed = SqlParser.parse(sql);

        if (!parsed.isSuccess()) {
            sendBadRequest(ctx, "Invalid SQL: " + sql, "http.sql");

            return;
        }

        switch (parsed.getType()) {
            case SELECT:
                handleSqlSelect(ctx, parsed);
                break;
            case INSERT:
                if (userPermission == PermissionType.READER) {
                    sendForbidden(ctx, "READER users cannot write");
                    return;
                }
                handleSqlInsert(ctx, parsed, session);
                break;
            case UPDATE:
                if (userPermission == PermissionType.READER) {
                    sendForbidden(ctx, "READER users cannot write");
                    return;
                }
                handleSqlUpdate(ctx, parsed, session);
                break;
            case DELETE:
                if (userPermission == PermissionType.READER) {
                    sendForbidden(ctx, "READER users cannot write");
                    return;
                }
                handleSqlDelete(ctx, parsed);
                break;
            default:
                sendBadRequest(ctx, "Unsupported SQL operation", "http.sql");

        }
    }

    private void handleSqlSelect(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed) {
        CacheResult result = cacheService.get(parsed.getKey());

        if (!result.isSuccess()) {
                sendNotFound(ctx, result.getMessage(),"http.notfound");
            return;
        }

        String formatted = SqlParser.formatResult(parsed.getKey(),
                result.getData().toString(), parsed.getColumns());

        sendResponse(ctx, HttpResponseStatus.OK, formatted);
    }

    private void handleSqlInsert(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed, Session session) {
        String dataType = SqlParser.inferDataType(parsed.getValue());
        DataType dt = DataType.fromString(dataType);

        CacheResult result = cacheService.post(
                parsed.getKey(),
                parsed.getValue(),
                dt,
                session.getCreator().getUsername(),
                null
        );

        if (result.isSuccess()) {
            sendResponse(ctx, HttpResponseStatus.OK, "OK");
        } else {
            sendBadRequest(ctx, result.getMessage(), "http.sql");
        }
    }

    private void handleSqlUpdate(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed, Session session) {
        String dataType = SqlParser.inferDataType(parsed.getValue());
        DataType dt = DataType.fromString(dataType);

        CacheResult result = cacheService.put(
                parsed.getKey(),
                parsed.getValue(),
                dt,
                session.getCreator().getUsername(),
                null
        );

        if (result.isSuccess()) {
            sendResponse(ctx, HttpResponseStatus.OK, "OK");
        } else {
            sendBadRequest(ctx, result.getMessage(), "http.sql");
        }
    }

    private void handleSqlDelete(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed) {
        CacheResult result = cacheService.delete(parsed.getKey());

        if (result.isSuccess()) {
            sendResponse(ctx, HttpResponseStatus.OK, "OK");
        } else {
            sendBadRequest(ctx, result.getMessage(), "http.sql");
        }
    }

    private String decodeUrl(String encoded) {
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.warn("Failed to decode URL: {}", encoded);
            return encoded;
        }
    }

    // ============ CACHE HANDLERS ============

    private void handleCacheRequest(ChannelHandlerContext ctx, HttpMethod method, String uri,
            String content, String requestType) {
        Map<String, String> params = parseUri(uri);
        String sessionToken = params.get("session_token");

        // Проверка сессии
        if (!isSessionValid(sessionToken)) {
            sendUnauthorized(ctx, "Invalid or expired session");
            return;
        }

        // ========== SQL ЗАПРОС (НОВЫЙ ФУНКЦИОНАЛ) ==========
        String sqlEncoded = params.get("sql");
        if (sqlEncoded != null && !sqlEncoded.isEmpty()) {
            String sql = decodeUrl(sqlEncoded);
            Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
            if (sessionOpt.isEmpty()) {
                sendUnauthorized(ctx, "Invalid session");
                return;
            }
            Session session = sessionOpt.get();
            PermissionType userPermission = session.getPermissionType();
            handleSqlQuery(ctx, sql, session, userPermission);
            return;
        }

        String key = params.get("key");
        if (key == null) {
            sendBadRequest(ctx, "Missing required parameters: session_token and key", requestType);

            return;
        }

        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        if (sessionOpt.isEmpty()) {
            sendUnauthorized(ctx, "Invalid or expired session");
            return;
        }

        Session session = sessionOpt.get();
        PermissionType userPermission = session.getPermissionType();

        FullHttpResponse response;

        try {
            if (method.isGet()) {
                response = handleCacheGet(key);
            } else if (method.isWriteMethod()) {
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
            String key, Map<String, String> params, String content) {
        String type = params.get("type");
        if (type == null) {
            return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing required parameter: type");
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
                    return createResponse(HttpResponseStatus.BAD_REQUEST, "TTL must be positive");
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

    // ============ AUTH HANDLER ============

    private void handleAuthRequest(ChannelHandlerContext ctx, HttpMethod method, String uri,
            String content, String requestType) {
        Map<String, String> params = parseUri(uri);
        FullHttpResponse response;

        try {
            if (method.isGet()) {
                response = handleAuthLogin(params);
            } else {
                response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED,
                        "Method " + method.getMethod() + " not allowed for /auth endpoint");
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
            if (user == null || !user.getPassword().equals(password)) {
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

    // ============ CONFIG HANDLER ============

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
                    "Missing required parameters: maxmemory_policy, max_storage_memory and persistence");
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

    // ============ USER HANDLERS ============

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
                return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to create user");
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
            return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing required parameter: login");
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
                return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to update user");
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
            return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing required parameter: login");
        }

        try {
            User existingUser = userDAO.findByUsername(login);
            if (existingUser == null) {
                return createResponse(HttpResponseStatus.NOT_FOUND,
                        "User with login '" + login + "' not found");
            }

            boolean success = userDAO.deleteUser(login);
            if (!success) {
                return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to delete user");
            }

            sessionService.removeSessionIfExists(existingUser);

            return createResponse(HttpResponseStatus.OK,
                    "User deleted successfully\nDeleted user: " + login);
        } catch (Exception e) {
            log.error("Error deleting user", e);
            return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Error deleting user: " + e.getMessage());
        }
    }

    // ============ RATE LIMIT STATS ============

    private void sendRateLimitStats(ChannelHandlerContext ctx, String requestType) {
        Map<String, Long> stats = rateLimitService.getStats();
        StringBuilder sb = new StringBuilder();
        sb.append("Rate Limit Statistics\n");
        sb.append("=".repeat(50)).append("\n\n");

        if (stats.isEmpty()) {
            sb.append("No active rate limit buckets\n");
        } else {
            stats.forEach((key, tokens) ->
                    sb.append(String.format("%-40s %d tokens\n",
                            key.length() > 40 ? key.substring(0, 37) + "..." : key, tokens)));
        }

        sb.append("\nRate Limiting: ").append(rateLimitService.isEnabled() ? "ENABLED" : "DISABLED");

        FullHttpResponse response = createResponse(HttpResponseStatus.OK, sb.toString());
        writeHttpResponse(ctx, response, requestType);
    }

    // ============ HELPER METHODS ============
    /**
     * Отдаёт текущую конфигурацию сервера (для драйвера)
     */
    private void handleShowConfiguration(ChannelHandlerContext ctx, String requestType) {
        StringBuilder response = new StringBuilder();
        response.append("persistence=").append(cacheService.isPersistenceEnabled()).append("\n");
        response.append("max_memory=").append(cacheService.getConfiguredMaxMemoryBytes()).append("\n");

        FullHttpResponse httpResponse = createResponse(HttpResponseStatus.OK, response.toString());
        writeHttpResponse(ctx, httpResponse, requestType);
    }

    private String getRateLimitKey(ChannelHandlerContext ctx, String uri) {
        String clientIp = ctx.channel().remoteAddress().toString();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return clientIp + ":" + path;
    }

    private String extractSessionToken(String uri) {
        if (uri.contains("session_token=")) {
            int start = uri.indexOf("session_token=") + 14;
            int end = uri.indexOf("&", start);
            if (end == -1) end = uri.indexOf(" ", start);
            if (end == -1) end = uri.length();
            return uri.substring(start, end);
        }
        return null;
    }

    private void sendTooManyRequests(ChannelHandlerContext ctx, String requestType) {
        FullHttpResponse response = createResponse(
                HttpResponseStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Please try again later."
        );
        writeHttpResponse(ctx, response, requestType);
    }

    private boolean isSessionValid(String sessionToken) {
        return sessionToken != null && sessionService.getValidSession(sessionToken).isPresent();
    }

    private boolean isSuperAdminSession(String sessionToken) {
        if (!isSessionValid(sessionToken)) return false;
        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        return sessionOpt.isPresent() &&
                sessionOpt.get().getPermissionType() == PermissionType.SUPERADMIN;
    }

    private Long parseTTL(String ttlString) throws IllegalArgumentException {
        if (ttlString == null || ttlString.trim().isEmpty()) return null;
        ttlString = ttlString.trim().toLowerCase();
        try {
            if (ttlString.endsWith("ms")) return Long.parseLong(ttlString.substring(0, ttlString.length() - 2));
            else if (ttlString.endsWith("s")) return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 1000;
            else if (ttlString.endsWith("m")) return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 60 * 1000;
            else if (ttlString.endsWith("h")) return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 60 * 60 * 1000;
            else if (ttlString.endsWith("d")) return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 24 * 60 * 60 * 1000;
            else return Long.parseLong(ttlString);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid TTL format: " + ttlString);
        }
    }

    private String formatTTL(long ttlMs) {
        if (ttlMs < 1000) return ttlMs + "ms";
        else if (ttlMs < 60 * 1000) return (ttlMs / 1000) + "s";
        else if (ttlMs < 60 * 60 * 1000) return (ttlMs / (60 * 1000)) + "m";
        else if (ttlMs < 24 * 60 * 60 * 1000) return (ttlMs / (60 * 60 * 1000)) + "h";
        else return (ttlMs / (24 * 60 * 60 * 1000)) + "d";
    }

    private void sendInfoPage(ChannelHandlerContext ctx, String requestType) {
        String info = "Cache Storage HTTP Server\n\n" +
                "Available endpoints:\n\n" +
                "AUTHENTICATION:\n" +
                "GET    /auth?login=USERNAME&password=PASSWORD\n\n" +
                "CACHE OPERATIONS:\n" +
                "GET    /cache?session_token=TOKEN&key=KEY\n" +
                "POST   /cache?session_token=TOKEN&key=KEY&type=TYPE&ttl=TTL (with body)\n" +
                "PUT    /cache?session_token=TOKEN&key=KEY&type=TYPE&ttl=TTL (with body)\n" +
                "DELETE /cache?session_token=TOKEN&key=KEY\n\n" +
                "SQL API (via HTTP):\n" +
                "GET    /cache?session_token=TOKEN&sql=SELECT+value+FROM+cache+WHERE+key+%3D+'...'\n" +
                "GET    /cache?session_token=TOKEN&sql=INSERT+INTO+cache+(key,+value)+VALUES+('...',+'...')\n" +
                "GET    /cache?session_token=TOKEN&sql=UPDATE+cache+SET+value+%3D+'...'+WHERE+key+%3D+'...'\n" +
                "GET    /cache?session_token=TOKEN&sql=DELETE+FROM+cache+WHERE+key+%3D+'...'\n\n" +
                "PIPELINE:\n" +
                "POST   /pipeline (JSON array of commands)\n\n" +
                "USER MANAGEMENT (SUPERADMIN only):\n" +
                "POST   /user?session_token=TOKEN&login=LOGIN&password=PASSWORD&permission=PERMISSION\n" +
                "PUT    /user?session_token=TOKEN&login=LOGIN&[new_login=LOGIN]&[password=PASSWORD]&[permission=PERMISSION]\n" +
                "DELETE /user?session_token=TOKEN&login=LOGIN\n\n" +
                "CONFIGURATION (SUPERADMIN only):\n" +
                "PUT    /configuration?session_token=TOKEN&maxmemory_policy=POLICY&max_storage_memory=SIZE&persistence=BOOL\n\n" +
                "MONITORING:\n" +
                "GET    /metrics - Prometheus metrics\n" +
                "GET    /ratelimit-stats - Rate limit statistics\n\n" +
                "RATE LIMITS:\n" +
                "  Unauthenticated: 5 req/s\n" +
                "  READER: 50 req/s\n" +
                "  ADMIN: 200 req/s\n" +
                "  SUPERADMIN: 500 req/s";

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

    private void sendUnauthorized(ChannelHandlerContext ctx, String message) {
        FullHttpResponse response = createResponse(HttpResponseStatus.UNAUTHORIZED, message);
        writeHttpResponse(ctx, response, "http.unauthorized");
    }

    private void sendForbidden(ChannelHandlerContext ctx, String message) {
        FullHttpResponse response = createResponse(HttpResponseStatus.FORBIDDEN, message);
        writeHttpResponse(ctx, response, "http.forbidden");
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content) {
        FullHttpResponse response = createResponse(status, content);
        writeHttpResponse(ctx, response, "http.response");
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
        if (queryStart >= 0) path = uri.substring(0, queryStart);

        if ("/".equals(path)) return "http.root." + normalizedMethod;
        if (path.startsWith("/pipeline")) return "http.pipeline." + normalizedMethod;
        if (path.startsWith("/cache")) return "http.cache." + normalizedMethod;
        if (path.startsWith("/auth")) return "http.auth." + normalizedMethod;
        if (path.startsWith("/configuration")) return "http.configuration." + normalizedMethod;
        if (path.startsWith("/user")) return "http.user." + normalizedMethod;
        if (path.startsWith("/metrics")) return "http.metrics." + normalizedMethod;
        if (path.startsWith("/ratelimit-stats")) return "http.ratelimit.stats." + normalizedMethod;
        return "http.unknown." + normalizedMethod;
    }

    private FullHttpResponse createResponse(HttpResponseStatus status, String content) {
        return createResponse(status, content, "text/plain; charset=UTF-8");
    }

    private FullHttpResponse createResponse(HttpResponseStatus status, String content, String contentType) {
        ByteBuf buffer = Unpooled.copiedBuffer(content, StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buffer);
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