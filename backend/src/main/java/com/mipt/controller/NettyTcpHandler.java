package com.mipt.controller;

import com.mipt.cache.CacheResult;
import com.mipt.model.BatchCommand;
import com.mipt.model.BatchResult;
import com.mipt.model.DataType;
import com.mipt.model.PermissionType;
import com.mipt.model.Session;
import com.mipt.service.BatchCommandService;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.service.RateLimitService;
import com.mipt.telemetry.TelemetryService;
import com.mipt.database.dao.UserDAO;
import com.mipt.model.User;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class NettyTcpHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(NettyTcpHandler.class);

    private final CacheStorageService cacheService;
    private final SessionService sessionService;
    private final UserDAO userDAO;
    private final TelemetryService telemetryService;
    private final RateLimitService rateLimitService;

    private String sessionToken = null;
    private final List<String> batchBuffer = new ArrayList<>();
    private boolean batchMode = false;
    private long currentCommandStartNanos = -1L;

    public NettyTcpHandler(CacheStorageService cacheService,
            SessionService sessionService,
            UserDAO userDAO,
            TelemetryService telemetryService,
            RateLimitService rateLimitService) {
        this.cacheService = cacheService;
        this.sessionService = sessionService;
        this.userDAO = userDAO;
        this.telemetryService = telemetryService;
        this.rateLimitService = rateLimitService;
    }

    public NettyTcpHandler(CacheStorageService cacheService,
            SessionService sessionService,
            UserDAO userDAO) {
        this(cacheService, sessionService, userDAO,
                TelemetryService.disabled(cacheService),
                new RateLimitService(sessionService, false));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String message) {
        message = message.trim();
        if (message.isEmpty()) {
            return;
        }
        currentCommandStartNanos = System.nanoTime();

        // ============ RATE LIMIT CHECK ============
        String rateLimitKey = getRateLimitKey(ctx);

        if ("EXEC".equalsIgnoreCase(message) || "END".equalsIgnoreCase(message)) {
            int batchSize = batchBuffer.size();
            if (rateLimitService.isEnabled() && !rateLimitService.allowBatchRequest(rateLimitKey,
                    batchSize)) {
                writeTcpResponse(ctx, "tcp.ratelimit", false,
                        "ERROR Rate limit exceeded. Please try again later.\n");
                batchMode = false;
                batchBuffer.clear();
                return;
            }
        } else if (!"BEGIN".equalsIgnoreCase(message) && !batchMode) {
            if (rateLimitService.isEnabled() && !rateLimitService.allowRequest(rateLimitKey,
                    sessionToken)) {
                writeTcpResponse(ctx, "tcp.ratelimit", false,
                        "ERROR Rate limit exceeded. Please try again later.\n");
                return;
            }
        }

        log.info("TCP received: {}", message);

        if ("BEGIN".equalsIgnoreCase(message)) {
            startBatchMode(ctx);
            return;
        }

        if ("EXEC".equalsIgnoreCase(message) || "END".equalsIgnoreCase(message)) {
            executeBatch(ctx);
            return;
        }

        if (batchMode) {
            batchBuffer.add(message);
            currentCommandStartNanos = -1L;
            ctx.writeAndFlush("QUEUED\n");
            return;
        }

        processSingleCommand(ctx, message);
    }

    private void processSingleCommand(ChannelHandlerContext ctx, String message) {
        String[] parts = message.split("\\s+", 6);
        String command = parts[0].toUpperCase();
        String requestType = "tcp." + command.toLowerCase();

        switch (command) {
            case "AUTH":
                handleAuth(ctx, parts, requestType);
                break;
            case "SQL":
                handleSqlCommand(ctx, parts, requestType);
                break;
            case "GET":
                handleGet(ctx, parts, requestType);
                break;
            case "SET":
            case "PUT":
                handleSet(ctx, parts, requestType);
                break;
            case "DELETE":
                handleDelete(ctx, parts, requestType);
                break;
            case "SHOW_CONFIG":
                handleShowConfig(ctx, requestType);
                break;
            case "CREATE_USER":
                handleCreateUser(ctx, parts, requestType);
                break;
            case "UPDATE_USER":
                handleUpdateUser(ctx, parts, requestType);
                break;
            case "DELETE_USER":
                handleDeleteUser(ctx, parts, requestType);
                break;
            case "UPDATE_CONFIG":
                handleUpdateConfig(ctx, parts, requestType);
                break;
            case "HSET":
                handleHashSet(ctx, parts, requestType);
                break;
            case "HGET":
                handleHashGet(ctx, parts, requestType);
                break;
            case "HDEL":
                handleHashDel(ctx, parts, requestType);
                break;
            case "HGETALL":
                handleHashGetAll(ctx, parts, requestType);
                break;
            case "HKEYS":
                handleHashKeys(ctx, parts, requestType);
                break;
            case "HLEN":
                handleHashLen(ctx, parts, requestType);
                break;
            case "LPUSH":
                handleListPush(ctx, parts, requestType, true);
                break;
            case "RPUSH":
                handleListPush(ctx, parts, requestType, false);
                break;
            case "LPOP":
                handleListPop(ctx, parts, requestType, true);
                break;
            case "RPOP":
                handleListPop(ctx, parts, requestType, false);
                break;
            case "LRANGE":
                handleListRange(ctx, parts, requestType);
                break;
            case "LLEN":
                handleListLen(ctx, parts, requestType);
                break;
            case "LINDEX":
                handleListIndex(ctx, parts, requestType);
                break;
            default:
                writeTcpResponse(ctx, "tcp.unknown", false,
                    "ERROR Unknown command. Available: AUTH, SQL, GET, SET, PUT, DELETE, " +
                        "CREATE_USER, UPDATE_USER, DELETE_USER, UPDATE_CONFIG, SHOW_CONFIG, BEGIN, EXEC\n");
        }
    }

    // ============ SQL COMMAND ============

    private void handleSqlCommand(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }

        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: SQL <sql_query>\n");
            return;
        }

        StringBuilder sqlBuilder = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (i > 1) {
                sqlBuilder.append(" ");
            }
            sqlBuilder.append(parts[i]);
        }
        String sql = sqlBuilder.toString();

        log.info("SQL query via TCP: {}", sql);

        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        if (sessionOpt.isEmpty()) {
            writeTcpResponse(ctx, requestType, false, "ERROR Session expired\n");
            return;
        }

        Session session = sessionOpt.get();
        PermissionType userPermission = session.getPermissionType();

        SqlParser.ParsedQuery parsed = SqlParser.parse(sql);

        if (!parsed.isSuccess()) {
            writeTcpResponse(ctx, requestType, false, "ERROR Invalid SQL: " + sql + "\n");
            return;
        }

        try {
            switch (parsed.getType()) {
                case SELECT:
                    handleSqlSelect(ctx, parsed, requestType);
                    break;
                case INSERT:
                    if (userPermission == PermissionType.READER) {
                        writeTcpResponse(ctx, requestType, false,
                                "ERROR READER users cannot write\n");
                        return;
                    }
                    handleSqlInsert(ctx, parsed, session, requestType);
                    break;
                case UPDATE:
                    if (userPermission == PermissionType.READER) {
                        writeTcpResponse(ctx, requestType, false,
                                "ERROR READER users cannot write\n");
                        return;
                    }
                    handleSqlUpdate(ctx, parsed, session, requestType);
                    break;
                case DELETE:
                    if (userPermission == PermissionType.READER) {
                        writeTcpResponse(ctx, requestType, false,
                                "ERROR READER users cannot write\n");
                        return;
                    }
                    handleSqlDelete(ctx, parsed, requestType);
                    break;
                default:
                    writeTcpResponse(ctx, requestType, false, "ERROR Unsupported SQL operation\n");
            }
        } catch (Exception e) {
            log.error("SQL execution error", e);
            writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
        }
    }

    private void handleSqlSelect(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed,
            String requestType) {
        CacheResult result = cacheService.get(parsed.getKey());
        if (!result.isSuccess()) {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
            return;
        }
        String formatted = SqlParser.formatResult(parsed.getKey(),
                result.getData().toString(), parsed.getColumns());
        writeTcpResponse(ctx, requestType, true, "OK " + formatted + "\n");
    }

    private void handleSqlInsert(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed,
            Session session, String requestType) {
        String dataType = SqlParser.inferDataType(parsed.getValue());
        DataType dt = DataType.fromString(dataType);
        CacheResult result = cacheService.post(parsed.getKey(), parsed.getValue(), dt,
                session.getCreator().getUsername(), null);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleSqlUpdate(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed,
            Session session, String requestType) {
        String dataType = SqlParser.inferDataType(parsed.getValue());
        DataType dt = DataType.fromString(dataType);
        CacheResult result = cacheService.put(parsed.getKey(), parsed.getValue(), dt,
                session.getCreator().getUsername(), null);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleSqlDelete(ChannelHandlerContext ctx, SqlParser.ParsedQuery parsed,
            String requestType) {
        CacheResult result = cacheService.delete(parsed.getKey());
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    // ============ STANDARD COMMANDS ============
    /**
     * Отдаёт текущую конфигурацию сервера по TCP
     */
    private void handleShowConfig(ChannelHandlerContext ctx, String requestType) {
        // Проверяем аутентификацию
        if (!isAuthenticated(ctx, requestType)) return;

        StringBuilder response = new StringBuilder();
        response.append("persistence=").append(cacheService.isPersistenceEnabled()).append("\n");
        response.append("max_memory=").append(cacheService.getConfiguredMaxMemoryBytes()).append("\n");

        writeTcpResponse(ctx, requestType, true, response.toString());
    }

    private void handleAuth(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (parts.length < 3) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: AUTH <login> <password>\n");
            return;
        }
        String login = parts[1];
        String password = parts[2];

        try {
            User user = userDAO.findByUsername(login);
            if (user == null || !user.getPassword().equals(password)) {
                writeTcpResponse(ctx, requestType, false, "ERROR Invalid login or password\n");
                return;
            }
            sessionToken = sessionService.createSessionForUser(user);
            writeTcpResponse(ctx, requestType, true, "OK session_token=" + sessionToken + "\n");
        } catch (Exception e) {
            log.error("TCP auth error", e);
            writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
        }
    }

    private void handleGet(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }
        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: GET <key>\n");
            return;
        }
        CacheResult result = cacheService.get(parts[1]);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getData() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleSet(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }
        if (!hasWritePermission(ctx, requestType)) {
            return;
        }
        if (parts.length < 4) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Usage: " + parts[0].toUpperCase() + " <key> <type> <value> [ttl]\n");
            return;
        }

        String key = parts[1];
        String type = parts[2];
        String value = parts[3];
        String ttl = parts.length >= 5 ? parts[4] : null;

        DataType dataType = DataType.fromString(type);
        if (dataType == null) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Invalid type. Valid: " + String.join(", ", DataType.getAllValues())
                            + "\n");
            return;
        }

        try {
            Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
            String username = sessionOpt.get().getCreator().getUsername();

            Long ttlSeconds = ttl != null ? parseTtl(ttl) : null;

            CacheResult result;
            if ("PUT".equals(parts[0].toUpperCase())) {
                result = cacheService.put(key, value, dataType, username, ttlSeconds);
            } else {
                result = cacheService.post(key, value, dataType, username, ttlSeconds);
            }

            if (result.isSuccess()) {
                String response = "OK " + result.getMessage();
                if (ttl != null) {
                    response += " (TTL: " + ttl + ")";
                }
                writeTcpResponse(ctx, requestType, true, response + "\n");
            } else {
                writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
            }
        } catch (Exception e) {
            log.error("TCP set error", e);
            writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
        }
    }

    private void handleDelete(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }
        if (!hasWritePermission(ctx, requestType)) {
            return;
        }
        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: DELETE <key>\n");
            return;
        }
        CacheResult result = cacheService.delete(parts[1]);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleCreateUser(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }
        if (!isSuperAdmin(ctx, requestType)) {
            return;
        }
        if (parts.length < 4) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Usage: CREATE_USER <login> <password> <permission>\n");
            return;
        }

        PermissionType permissionType = PermissionType.fromString(parts[3]);
        if (permissionType == null) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Invalid permission. Valid: " + String.join(", ",
                            PermissionType.getAllValues()) + "\n");
            return;
        }

        try {
            if (userDAO.findByUsername(parts[1]) != null) {
                writeTcpResponse(ctx, requestType, false, "ERROR User already exists\n");
                return;
            }

            User newUser = userDAO.createUser(parts[1], parts[2], permissionType);
            if (newUser != null) {
                writeTcpResponse(ctx, requestType, true,
                        "OK User created: " + newUser.getUsername() + " (ID: " + newUser.getId()
                                + ")\n");
            } else {
                writeTcpResponse(ctx, requestType, false, "ERROR Failed to create user\n");
            }
        } catch (Exception e) {
            log.error("TCP create user error", e);
            writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
        }
    }

    private void handleUpdateUser(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }
        if (!isSuperAdmin(ctx, requestType)) {
            return;
        }
        if (parts.length < 5) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Usage: UPDATE_USER <login> <newLogin> <password> <permission>\n");
            return;
        }

        try {
            User existingUser = userDAO.findByUsername(parts[1]);
            if (existingUser == null) {
                writeTcpResponse(ctx, requestType, false, "ERROR User not found\n");
                return;
            }

            sessionService.removeSessionIfExists(existingUser);

            PermissionType permissionType = PermissionType.fromString(parts[4]);
            if (permissionType == null) {
                writeTcpResponse(ctx, requestType, false,
                        "ERROR Invalid permission. Valid: " + String.join(", ",
                                PermissionType.getAllValues()) + "\n");
                return;
            }

            boolean success = userDAO.updateUser(parts[1], parts[2], parts[3], permissionType);
            if (success) {
                writeTcpResponse(ctx, requestType, true, "OK User updated: " + parts[2] + "\n");
            } else {
                writeTcpResponse(ctx, requestType, false, "ERROR Failed to update user\n");
            }
        } catch (Exception e) {
            log.error("TCP update user error", e);
            writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
        }
    }

    private void handleDeleteUser(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }
        if (!isSuperAdmin(ctx, requestType)) {
            return;
        }
        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: DELETE_USER <login>\n");
            return;
        }

        try {
            User existingUser = userDAO.findByUsername(parts[1]);
            if (existingUser == null) {
                writeTcpResponse(ctx, requestType, false, "ERROR User not found\n");
                return;
            }

            boolean success = userDAO.deleteUser(parts[1]);
            if (success) {
                sessionService.removeSessionIfExists(existingUser);
                writeTcpResponse(ctx, requestType, true, "OK User deleted: " + parts[1] + "\n");
            } else {
                writeTcpResponse(ctx, requestType, false, "ERROR Failed to delete user\n");
            }
        } catch (Exception e) {
            log.error("TCP delete user error", e);
            writeTcpResponse(ctx, requestType, false, "ERROR " + e.getMessage() + "\n");
        }
    }

    private void handleUpdateConfig(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) {
            return;
        }
        if (!isSuperAdmin(ctx, requestType)) {
            return;
        }
        if (parts.length < 4) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Usage: UPDATE_CONFIG <policy> <maxMemory> <persistence>\n");
            return;
        }

        BatchCommand cmd = new BatchCommand();
        cmd.setCommand("PUT");
        cmd.setEndpoint("configuration");
        cmd.setMaxMemoryPolicy(parts[1]);
        cmd.setMaxStorageMemory(parts[2]);
        cmd.setPersistence(parts[3]);

        BatchCommandService batchService = new BatchCommandService(cacheService, sessionService,
                userDAO);
        batchService.setSessionToken(sessionToken);

        BatchResult result = batchService.executeBatch(List.of(cmd));

        if (!result.getResults().isEmpty()) {
            BatchResult.CommandResult cmdResult = result.getResults().get(0);
            if (cmdResult.isSuccess()) {
                writeTcpResponse(ctx, requestType, true, "OK " + cmdResult.getResult() + "\n");
            } else {
                writeTcpResponse(ctx, requestType, false, "ERROR " + cmdResult.getResult() + "\n");
            }
        }
    }

    // ============ BATCH MODE ============

    private void startBatchMode(ChannelHandlerContext ctx) {
        batchMode = true;
        batchBuffer.clear();
        writeTcpResponse(ctx, "tcp.batch.start", true,
                "BATCH_MODE_STARTED\nEnter commands, then EXEC to execute\n");
    }

    private void executeBatch(ChannelHandlerContext ctx) {
        if (!batchMode || batchBuffer.isEmpty()) {
            writeTcpResponse(ctx, "tcp.batch.exec", false, "ERROR No commands in batch buffer\n");
            batchMode = false;
            return;
        }

        try {
            List<BatchCommand> commands = new ArrayList<>();
            for (String cmdStr : batchBuffer) {
                BatchCommand cmd = parseTcpCommandToBatch(cmdStr);
                if (cmd != null) {
                    commands.add(cmd);
                }
            }

            BatchCommandService batchService = new BatchCommandService(cacheService, sessionService,
                    userDAO);
            batchService.setSessionToken(sessionToken);

            BatchResult batchResult = batchService.executeBatch(commands);

            StringBuilder response = new StringBuilder();
            response.append("BATCH_RESULT\n");
            response.append("─".repeat(50)).append("\n");

            for (BatchResult.CommandResult result : batchResult.getResults()) {
                String status = result.isSuccess() ? "✓" : "✗";
                response.append(String.format("[%d] %s %s: %s\n",
                        result.getIndex(), status, result.getCommand(), result.getResult()));
            }

            response.append("─".repeat(50)).append("\n");
            response.append("END_BATCH\n");

            writeTcpResponse(ctx, "tcp.batch.exec", batchResult.isSuccess(), response.toString());

        } catch (Exception e) {
            log.error("Error executing batch", e);
            writeTcpResponse(ctx, "tcp.batch.exec", false, "ERROR " + e.getMessage() + "\n");
        } finally {
            batchMode = false;
            batchBuffer.clear();
        }
    }

    private BatchCommand parseTcpCommandToBatch(String commandStr) {
        String[] parts = commandStr.split("\\s+", 6);
        if (parts.length == 0) {
            return null;
        }

        BatchCommand cmd = new BatchCommand();
        String command = parts[0].toUpperCase();

        switch (command) {
            case "AUTH":
                cmd.setCommand("GET");
                cmd.setEndpoint("auth");
                if (parts.length >= 3) {
                    cmd.setLogin(parts[1]);
                    cmd.setPassword(parts[2]);
                }
                break;
            case "GET":
                cmd.setCommand("GET");
                cmd.setEndpoint("cache");
                if (parts.length >= 2) {
                    cmd.setKey(parts[1]);
                }
                break;
            case "SET":
                cmd.setCommand("POST");
                cmd.setEndpoint("cache");
                if (parts.length >= 4) {
                    cmd.setKey(parts[1]);
                    cmd.setType(parts[2]);
                    cmd.setValue(parts[3]);
                }
                if (parts.length >= 5) {
                    cmd.setTtl(parts[4]);
                }
                break;
            case "PUT":
                cmd.setCommand("PUT");
                cmd.setEndpoint("cache");
                if (parts.length >= 4) {
                    cmd.setKey(parts[1]);
                    cmd.setType(parts[2]);
                    cmd.setValue(parts[3]);
                }
                if (parts.length >= 5) {
                    cmd.setTtl(parts[4]);
                }
                break;
            case "DELETE":
                cmd.setCommand("DELETE");
                cmd.setEndpoint("cache");
                if (parts.length >= 2) {
                    cmd.setKey(parts[1]);
                }
                break;
            case "CREATE_USER":
                cmd.setCommand("POST");
                cmd.setEndpoint("user");
                if (parts.length >= 4) {
                    cmd.setLogin(parts[1]);
                    cmd.setPassword(parts[2]);
                    cmd.setPermission(parts[3]);
                }
                break;
            case "UPDATE_USER":
                cmd.setCommand("PUT");
                cmd.setEndpoint("user");
                if (parts.length >= 5) {
                    cmd.setLogin(parts[1]);
                    cmd.setNewLogin(parts[2]);
                    cmd.setPassword(parts[3]);
                    cmd.setPermission(parts[4]);
                }
                break;
            case "DELETE_USER":
                cmd.setCommand("DELETE");
                cmd.setEndpoint("user");
                if (parts.length >= 2) {
                    cmd.setLogin(parts[1]);
                }
                break;
            case "UPDATE_CONFIG":
                cmd.setCommand("PUT");
                cmd.setEndpoint("configuration");
                if (parts.length >= 4) {
                    cmd.setMaxMemoryPolicy(parts[1]);
                    cmd.setMaxStorageMemory(parts[2]);
                    cmd.setPersistence(parts[3]);
                }
                break;
            default:
                return null;
        }
        return cmd;
    }

    // ============ HELPER METHODS ============

    private String getRateLimitKey(ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress().toString();
    }

    private boolean isAuthenticated(ChannelHandlerContext ctx, String requestType) {
        if (sessionToken == null || sessionService.getValidSession(sessionToken).isEmpty()) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Not authenticated. Use: AUTH <login> <password>\n");
            return false;
        }
        return true;
    }

    private boolean hasWritePermission(ChannelHandlerContext ctx, String requestType) {
        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        if (sessionOpt.isPresent()
                && sessionOpt.get().getPermissionType() == PermissionType.READER) {
            writeTcpResponse(ctx, requestType, false, "ERROR READER users can only GET\n");
            return false;
        }
        return true;
    }

    private boolean isSuperAdmin(ChannelHandlerContext ctx, String requestType) {
        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        if (sessionOpt.isPresent()
                && sessionOpt.get().getPermissionType() != PermissionType.SUPERADMIN) {
            writeTcpResponse(ctx, requestType, false,
                    "ERROR Only SUPERADMIN users can perform this operation\n");
            return false;
        }
        return true;
    }

    private Long parseTtl(String ttlString) {
        if (ttlString == null || ttlString.trim().isEmpty()) {
            return null;
        }
        ttlString = ttlString.trim().toLowerCase();
        try {
            if (ttlString.endsWith("ms")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 2)) / 1000;
            } else if (ttlString.endsWith("s")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1));
            } else if (ttlString.endsWith("m")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 60;
            } else if (ttlString.endsWith("h")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 3600;
            } else if (ttlString.endsWith("d")) {
                return Long.parseLong(ttlString.substring(0, ttlString.length() - 1)) * 86400;
            } else {
                return Long.parseLong(ttlString) / 1000;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid TTL format: " + ttlString);
        }
    }

    private void writeTcpResponse(ChannelHandlerContext ctx, String requestType,
            boolean success, String message) {
        long latencyNanos = currentCommandStartNanos > 0
                ? System.nanoTime() - currentCommandStartNanos
                : -1L;
        telemetryService.recordTcpRequest(requestType, success, latencyNanos);
        currentCommandStartNanos = -1L;
        ctx.writeAndFlush(message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isClientDisconnect(cause)) {
            log.debug("TCP client disconnected: {}", cause.getMessage());
            ctx.close();
            return;
        }

        log.error("TCP error", cause);
        ctx.close();
    }

    // ============ HASH COMMANDS ============

    private HashCommandHandler hashHandler;
    private ListCommandHandler listHandler;

    private HashCommandHandler getHashHandler() {
        if (hashHandler == null) {
            hashHandler = new HashCommandHandler(new com.mipt.cache.HashCache());
        }
        return hashHandler;
    }

    private ListCommandHandler getListHandler() {
        if (listHandler == null) {
            listHandler = new ListCommandHandler(new com.mipt.cache.ListCache());
        }
        return listHandler;
    }

    private void handleHashSet(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (!hasWritePermission(ctx, requestType)) return;
        if (parts.length < 4) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: HSET <key> <field> <value>\n");
            return;
        }

        String key = parts[1];
        String field = parts[2];
        String value = parts[3];

        CacheResult result = getHashHandler().hset(key, field, value);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleHashGet(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (parts.length < 3) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: HGET <key> <field>\n");
            return;
        }

        String key = parts[1];
        String field = parts[2];

        CacheResult result = getHashHandler().hget(key, field);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getData() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleHashDel(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (!hasWritePermission(ctx, requestType)) return;
        if (parts.length < 3) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: HDEL <key> <field>\n");
            return;
        }

        String key = parts[1];
        String field = parts[2];

        CacheResult result = getHashHandler().hdel(key, field);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleHashGetAll(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: HGETALL <key>\n");
            return;
        }

        String key = parts[1];

        CacheResult result = getHashHandler().hgetall(key);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK\n" + result.getData());
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleHashKeys(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: HKEYS <key>\n");
            return;
        }

        String key = parts[1];

        CacheResult result = getHashHandler().hkeys(key);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK\n" + result.getData());
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleHashLen(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: HLEN <key>\n");
            return;
        }

        String key = parts[1];

        CacheResult result = getHashHandler().hlen(key);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    // ============ LIST COMMANDS ============

    private void handleListPush(ChannelHandlerContext ctx, String[] parts, String requestType, boolean left) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (!hasWritePermission(ctx, requestType)) return;
        if (parts.length < 3) {
            String cmd = left ? "LPUSH" : "RPUSH";
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: " + cmd + " <key> <value>\n");
            return;
        }

        String key = parts[1];
        String value = parts[2];

        CacheResult result;
        if (left) {
            result = getListHandler().lpush(key, value);
        } else {
            result = getListHandler().rpush(key, value);
        }

        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleListPop(ChannelHandlerContext ctx, String[] parts, String requestType, boolean left) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (!hasWritePermission(ctx, requestType)) return;
        if (parts.length < 2) {
            String cmd = left ? "LPOP" : "RPOP";
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: " + cmd + " <key>\n");
            return;
        }

        String key = parts[1];

        CacheResult result;
        if (left) {
            result = getListHandler().lpop(key);
        } else {
            result = getListHandler().rpop(key);
        }

        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleListRange(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (parts.length < 4) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: LRANGE <key> <start> <end>\n");
            return;
        }

        String key = parts[1];
        int start, end;

        try {
            start = Integer.parseInt(parts[2]);
            end = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            writeTcpResponse(ctx, requestType, false, "ERROR Invalid start or end index\n");
            return;
        }

        CacheResult result = getListHandler().lrange(key, start, end);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK\n" + result.getData());
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleListLen(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (parts.length < 2) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: LLEN <key>\n");
            return;
        }

        String key = parts[1];

        CacheResult result = getListHandler().llen(key);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private void handleListIndex(ChannelHandlerContext ctx, String[] parts, String requestType) {
        if (!isAuthenticated(ctx, requestType)) return;
        if (parts.length < 3) {
            writeTcpResponse(ctx, requestType, false, "ERROR Usage: LINDEX <key> <index>\n");
            return;
        }

        String key = parts[1];
        int index;

        try {
            index = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            writeTcpResponse(ctx, requestType, false, "ERROR Invalid index\n");
            return;
        }

        CacheResult result = getListHandler().lindex(key, index);
        if (result.isSuccess()) {
            writeTcpResponse(ctx, requestType, true, "OK " + result.getMessage() + "\n");
        } else {
            writeTcpResponse(ctx, requestType, false, "ERROR " + result.getMessage() + "\n");
        }
    }

    private boolean isClientDisconnect(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof SocketException) {
                String message = current.getMessage();
                if (message != null) {
                    String normalized = message.toLowerCase(Locale.ROOT);
                    if (normalized.contains("connection reset")
                            || normalized.contains("broken pipe")
                            || normalized.contains("forcibly closed")) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

}
