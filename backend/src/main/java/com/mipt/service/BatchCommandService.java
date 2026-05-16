package com.mipt.service;

import com.mipt.cache.CacheResult;
import com.mipt.model.*;
import com.mipt.database.dao.UserDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class BatchCommandService {
    private static final Logger log = LoggerFactory.getLogger(BatchCommandService.class);

    private final CacheStorageService cacheService;
    private final SessionService sessionService;
    private final UserDAO userDAO;
    private String sessionToken;

    public BatchCommandService(CacheStorageService cacheService,
            SessionService sessionService,
            UserDAO userDAO) {
        this.cacheService = cacheService;
        this.sessionService = sessionService;
        this.userDAO = userDAO;
    }

    public BatchResult executeBatch(List<BatchCommand> commands) {
        BatchResult batchResult = new BatchResult();
        batchResult.setSuccess(true);

        for (int i = 0; i < commands.size(); i++) {
            BatchCommand command = commands.get(i);
            try {
                if (command.getSessionToken() != null) {
                    this.sessionToken = command.getSessionToken();
                }

                BatchResult.CommandResult result = executeCommand(i, command);
                batchResult.addResult(result);

                if (!result.isSuccess()) {
                    if (shouldStopOnError(command)) {
                        batchResult.setSuccess(false);
                        batchResult.setMessage("Pipeline stopped at command " + (i + 1) +
                                ": " + result.getResult());
                        break;
                    }
                    batchResult.setSuccess(false);
                }
            } catch (Exception e) {
                log.error("Error executing batch command {}: {}", i, command.getCommand(), e);
                batchResult.addResult(new BatchResult.CommandResult(
                        i, command.getCommand(), false, "Error: " + e.getMessage()
                ));
                batchResult.setSuccess(false);
                if (shouldStopOnError(command)) {
                    batchResult.setMessage("Error at command " + (i + 1) + ": " + e.getMessage());
                    break;
                }
            }
        }

        return batchResult;
    }

    private BatchResult.CommandResult executeCommand(int index, BatchCommand command) {
        String method = command.getCommand().toUpperCase();
        String endpoint = command.getEndpoint();

        if (endpoint == null) {
            endpoint = determineEndpoint(method, command);
        }

        switch (endpoint.toLowerCase()) {
            case "auth":
                return executeAuth(index, command);
            case "cache":
                return executeCacheOperation(index, method, command);
            case "user":
                return executeUserOperation(index, method, command);
            case "configuration":
                return executeConfigOperation(index, method, command);
            default:
                return new BatchResult.CommandResult(index, method, false,
                        "Unknown endpoint: " + endpoint + ". Available: auth, cache, user, configuration");
        }
    }

    private String determineEndpoint(String method, BatchCommand command) {
        if ("AUTH".equals(method) || "GET".equalsIgnoreCase(method) &&
                command.getLogin() != null && command.getPassword() != null &&
                command.getKey() == null) {
            return "auth";
        }

        if (command.getKey() != null) {
            return "cache";
        }

        if (command.getLogin() != null || command.getNewLogin() != null ||
                command.getPermission() != null) {
            return "user";
        }

        if (command.getMaxMemoryPolicy() != null || command.getMaxStorageMemory() != null ||
                command.getPersistence() != null) {
            return "configuration";
        }
        return "cache";
    }

    private boolean shouldStopOnError(BatchCommand command) {
        String method = command.getCommand().toUpperCase();
        return !"AUTH".equals(method);
    }

    // ============ AUTH (GET /auth) ============
    private BatchResult.CommandResult executeAuth(int index, BatchCommand command) {
        if (!"GET".equalsIgnoreCase(command.getCommand())) {
            return new BatchResult.CommandResult(index, "AUTH", false,
                    "AUTH endpoint only supports GET method");
        }

        if (command.getLogin() == null || command.getPassword() == null) {
            return new BatchResult.CommandResult(index, "AUTH", false,
                    "Missing login or password");
        }

        try {
            User user = userDAO.findByUsername(command.getLogin());
            if (user == null || !user.getPassword().equals(command.getPassword())) {
                return new BatchResult.CommandResult(index, "AUTH", false,
                        "Invalid login or password");
            }

            sessionToken = sessionService.createSessionForUser(user);
            return new BatchResult.CommandResult(index, "AUTH", true,
                    "Authentication successful. Session token: " + sessionToken);
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "AUTH", false,
                    "Error: " + e.getMessage());
        }
    }

    // ============ CACHE OPERATIONS ============
    private BatchResult.CommandResult executeCacheOperation(int index, String method,
            BatchCommand command) {
        switch (method) {
            case "GET":
                return executeCacheGet(index, command);
            case "POST":
                return executeCachePost(index, command);
            case "PUT":
                return executeCachePut(index, command);
            case "DELETE":
                return executeCacheDelete(index, command);
            default:
                return new BatchResult.CommandResult(index, method, false,
                        "Invalid HTTP method for cache: " + method + ". Use GET, POST, PUT, DELETE");
        }
    }

    // GET /cache
    private BatchResult.CommandResult executeCacheGet(int index, BatchCommand command) {
        if (!isAuthenticated()) {
            return new BatchResult.CommandResult(index, "GET", false,
                    "Not authenticated. Use AUTH first");
        }

        if (command.getKey() == null) {
            return new BatchResult.CommandResult(index, "GET", false,
                    "Missing key parameter");
        }

        try {
            CacheResult result = cacheService.get(command.getKey());
            if (result.isSuccess()) {
                return new BatchResult.CommandResult(index, "GET", true,
                        result.getData().toString());
            } else {
                return new BatchResult.CommandResult(index, "GET", false,
                        result.getMessage());
            }
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "GET", false,
                    "Error: " + e.getMessage());
        }
    }

    // POST /cache (insert)
    private BatchResult.CommandResult executeCachePost(int index, BatchCommand command) {
        if (!isAuthenticated()) {
            return new BatchResult.CommandResult(index, "POST", false,
                    "Not authenticated. Use AUTH first");
        }

        if (!hasWritePermission()) {
            return new BatchResult.CommandResult(index, "POST", false,
                    "READER users can only GET data");
        }

        if (command.getKey() == null || command.getType() == null || command.getValue() == null) {
            return new BatchResult.CommandResult(index, "POST", false,
                    "Missing required parameters: key, type, value");
        }

        DataType dataType = DataType.fromString(command.getType());
        if (dataType == null) {
            return new BatchResult.CommandResult(index, "POST", false,
                    "Invalid type: " + command.getType() + ". Valid: " +
                            String.join(", ", DataType.getAllValues()));
        }

        try {
            Long ttlSeconds = parseTtl(command.getTtl());
            String username = getCurrentUsername();

            CacheResult result = cacheService.post(
                    command.getKey(), command.getValue(), dataType, username, ttlSeconds);

            String responseMsg = result.getMessage();
            if (result.isSuccess() && ttlSeconds != null) {
                responseMsg += " (TTL: " + command.getTtl() + ")";
            }

            return new BatchResult.CommandResult(index, "POST", result.isSuccess(), responseMsg);
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "POST", false,
                    "Error: " + e.getMessage());
        }
    }

    // PUT /cache (update)
    private BatchResult.CommandResult executeCachePut(int index, BatchCommand command) {
        if (!isAuthenticated()) {
            return new BatchResult.CommandResult(index, "PUT", false,
                    "Not authenticated. Use AUTH first");
        }

        if (!hasWritePermission()) {
            return new BatchResult.CommandResult(index, "PUT", false,
                    "READER users can only GET data");
        }

        if (command.getKey() == null || command.getType() == null || command.getValue() == null) {
            return new BatchResult.CommandResult(index, "PUT", false,
                    "Missing required parameters: key, type, value");
        }

        DataType dataType = DataType.fromString(command.getType());
        if (dataType == null) {
            return new BatchResult.CommandResult(index, "PUT", false,
                    "Invalid type: " + command.getType() + ". Valid: " +
                            String.join(", ", DataType.getAllValues()));
        }

        try {
            Long ttlSeconds = parseTtl(command.getTtl());
            String username = getCurrentUsername();

            CacheResult result = cacheService.put(
                    command.getKey(), command.getValue(), dataType, username, ttlSeconds);

            String responseMsg = result.getMessage();
            if (result.isSuccess() && ttlSeconds != null) {
                responseMsg += " (TTL: " + command.getTtl() + ")";
            }

            return new BatchResult.CommandResult(index, "PUT", result.isSuccess(), responseMsg);
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "PUT", false,
                    "Error: " + e.getMessage());
        }
    }

    // DELETE /cache
    private BatchResult.CommandResult executeCacheDelete(int index, BatchCommand command) {
        if (!isAuthenticated()) {
            return new BatchResult.CommandResult(index, "DELETE", false,
                    "Not authenticated. Use AUTH first");
        }

        if (!hasWritePermission()) {
            return new BatchResult.CommandResult(index, "DELETE", false,
                    "READER users can only GET data");
        }

        if (command.getKey() == null) {
            return new BatchResult.CommandResult(index, "DELETE", false,
                    "Missing key parameter");
        }

        try {
            CacheResult result = cacheService.delete(command.getKey());
            return new BatchResult.CommandResult(index, "DELETE", result.isSuccess(),
                    result.getMessage());
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "DELETE", false,
                    "Error: " + e.getMessage());
        }
    }

    // ============ USER OPERATIONS ============
    private BatchResult.CommandResult executeUserOperation(int index, String method,
            BatchCommand command) {
        if (sessionToken == null) {
            return new BatchResult.CommandResult(index, method, false,
                    "Not authenticated. Use AUTH first");
        }

        if (!isSuperAdmin()) {
            return new BatchResult.CommandResult(index, method, false,
                    "Only SUPERADMIN users can manage users");
        }

        switch (method) {
            case "POST":
                return executeCreateUser(index, command);
            case "PUT":
                return executeUpdateUser(index, command);
            case "DELETE":
                return executeDeleteUser(index, command);
            default:
                return new BatchResult.CommandResult(index, method, false,
                        "Invalid HTTP method for user endpoint: " + method + ". Use POST, PUT, DELETE");
        }
    }

    // POST /user (create)
    private BatchResult.CommandResult executeCreateUser(int index, BatchCommand command) {
        if (command.getLogin() == null || command.getPassword() == null ||
                command.getPermission() == null) {
            return new BatchResult.CommandResult(index, "POST /user", false,
                    "Missing required parameters: login, password, permission");
        }

        PermissionType permissionType = PermissionType.fromString(command.getPermission());
        if (permissionType == null) {
            return new BatchResult.CommandResult(index, "POST /user", false,
                    "Invalid permission: " + command.getPermission() + ". Valid: " +
                            String.join(", ", PermissionType.getAllValues()));
        }

        try {
            User existingUser = userDAO.findByUsername(command.getLogin());
            if (existingUser != null) {
                return new BatchResult.CommandResult(index, "POST /user", false,
                        "User with login '" + command.getLogin() + "' already exists");
            }

            User newUser = userDAO.createUser(command.getLogin(), command.getPassword(), permissionType);

            if (newUser == null) {
                return new BatchResult.CommandResult(index, "POST /user", false,
                        "Failed to create user");
            }

            return new BatchResult.CommandResult(index, "POST /user", true,
                    "User created: " + newUser.getUsername() + " (ID: " + newUser.getId() + ")");
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "POST /user", false,
                    "Error: " + e.getMessage());
        }
    }

    // PUT /user (update)
    private BatchResult.CommandResult executeUpdateUser(int index, BatchCommand command) {
        if (command.getLogin() == null) {
            return new BatchResult.CommandResult(index, "PUT /user", false,
                    "Missing required parameter: login");
        }

        // Хотя бы один параметр для обновления
        if (command.getNewLogin() == null && command.getPassword() == null &&
                command.getPermission() == null) {
            return new BatchResult.CommandResult(index, "PUT /user", false,
                    "At least one update parameter required: newLogin, password, or permission");
        }

        try {
            User existingUser = userDAO.findByUsername(command.getLogin());
            if (existingUser == null) {
                return new BatchResult.CommandResult(index, "PUT /user", false,
                        "User not found: " + command.getLogin());
            }

            sessionService.removeSessionIfExists(existingUser);

            PermissionType permissionType = null;
            if (command.getPermission() != null) {
                permissionType = PermissionType.fromString(command.getPermission());
                if (permissionType == null) {
                    return new BatchResult.CommandResult(index, "PUT /user", false,
                            "Invalid permission: " + command.getPermission());
                }
            }

            boolean success = userDAO.updateUser(
                    command.getLogin(), command.getNewLogin(),
                    command.getPassword(), permissionType);

            if (!success) {
                return new BatchResult.CommandResult(index, "PUT /user", false,
                        "Failed to update user");
            }

            String updatedLogin = command.getNewLogin() != null ? command.getNewLogin() : command.getLogin();
            return new BatchResult.CommandResult(index, "PUT /user", true,
                    "User updated: " + updatedLogin);
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "PUT /user", false,
                    "Error: " + e.getMessage());
        }
    }

    // DELETE /user
    private BatchResult.CommandResult executeDeleteUser(int index, BatchCommand command) {
        if (command.getLogin() == null) {
            return new BatchResult.CommandResult(index, "DELETE /user", false,
                    "Missing required parameter: login");
        }

        try {
            User existingUser = userDAO.findByUsername(command.getLogin());
            if (existingUser == null) {
                return new BatchResult.CommandResult(index, "DELETE /user", false,
                        "User not found: " + command.getLogin());
            }

            boolean success = userDAO.deleteUser(command.getLogin());

            if (!success) {
                return new BatchResult.CommandResult(index, "DELETE /user", false,
                        "Failed to delete user");
            }

            sessionService.removeSessionIfExists(existingUser);

            return new BatchResult.CommandResult(index, "DELETE /user", true,
                    "User deleted: " + command.getLogin());
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "DELETE /user", false,
                    "Error: " + e.getMessage());
        }
    }

    // ============ CONFIGURATION (PUT /configuration) ============
    private BatchResult.CommandResult executeConfigOperation(int index, String method,
            BatchCommand command) {
        if (!"PUT".equalsIgnoreCase(method)) {
            return new BatchResult.CommandResult(index, method, false,
                    "Configuration endpoint only supports PUT method");
        }

        if (sessionToken == null) {
            return new BatchResult.CommandResult(index, "PUT /configuration", false,
                    "Not authenticated. Use AUTH first");
        }

        if (!isSuperAdmin()) {
            return new BatchResult.CommandResult(index, "PUT /configuration", false,
                    "Only SUPERADMIN users can update configuration");
        }

        if (command.getMaxMemoryPolicy() == null || command.getMaxStorageMemory() == null ||
                command.getPersistence() == null) {
            return new BatchResult.CommandResult(index, "PUT /configuration", false,
                    "Missing required parameters: maxMemoryPolicy, maxStorageMemory, persistence");
        }

        try {
            MaxMemoryPolicy policy = MaxMemoryPolicy.fromString(command.getMaxMemoryPolicy());
            if (policy == null) {
                return new BatchResult.CommandResult(index, "PUT /configuration", false,
                        "Invalid maxMemoryPolicy: " + command.getMaxMemoryPolicy());
            }

            long maxStorageMemory;
            try {
                maxStorageMemory = Long.parseLong(command.getMaxStorageMemory());
                if (maxStorageMemory <= 0) {
                    return new BatchResult.CommandResult(index, "PUT /configuration", false,
                            "maxStorageMemory must be positive");
                }
            } catch (NumberFormatException e) {
                return new BatchResult.CommandResult(index, "PUT /configuration", false,
                        "Invalid maxStorageMemory: " + command.getMaxStorageMemory());
            }

            boolean persistence;
            try {
                persistence = Boolean.parseBoolean(command.getPersistence());
            } catch (Exception e) {
                return new BatchResult.CommandResult(index, "PUT /configuration", false,
                        "Invalid persistence: " + command.getPersistence());
            }

            CacheResult result = cacheService.changePolicy(policy, maxStorageMemory, persistence);

            if (!result.isSuccess()) {
                return new BatchResult.CommandResult(index, "PUT /configuration", false,
                        result.getMessage());
            }

            return new BatchResult.CommandResult(index, "PUT /configuration", true,
                    "Configuration updated: policy=" + policy.getValue() +
                            ", memory=" + maxStorageMemory + ", persistence=" + persistence);
        } catch (Exception e) {
            return new BatchResult.CommandResult(index, "PUT /configuration", false,
                    "Error: " + e.getMessage());
        }
    }

    // ============ HELPER METHODS ============
    private boolean isAuthenticated() {
        return sessionToken != null && isSessionValid();
    }

    private boolean isSessionValid() {
        if (sessionToken == null) return false;
        return sessionService.getValidSession(sessionToken).isPresent();
    }

    private boolean hasWritePermission() {
        if (!isSessionValid()) return false;
        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        return sessionOpt.isPresent() &&
                sessionOpt.get().getPermissionType() != PermissionType.READER;
    }

    private boolean isSuperAdmin() {
        if (!isSessionValid()) return false;
        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        return sessionOpt.isPresent() &&
                sessionOpt.get().getPermissionType() == PermissionType.SUPERADMIN;
    }

    private String getCurrentUsername() {
        Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
        return sessionOpt.map(session -> session.getCreator().getUsername()).orElse("unknown");
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
                return Long.parseLong(ttlString) / 1000; // plain milliseconds
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid TTL format: " + ttlString);
        }
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String getSessionToken() {
        return sessionToken;
    }
}