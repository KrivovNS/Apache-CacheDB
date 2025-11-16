package com.mipt.controller.Handler;

import com.mipt.cache.CacheStorage;
import com.mipt.controller.RequestParametersValidator;
import com.mipt.controller.UserRole;
import com.mipt.userstorage.dao.UserDAO;
import com.mipt.userstorage.model.User;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;
import java.util.UUID;

public class StorageRequestHandler extends BaseNettyHandler {

  private final CacheStorage cacheStorage;
  private final UserDAO userDAO;
  private final RequestParametersValidator validator;

  public StorageRequestHandler(CacheStorage cacheStorage, UserDAO userDAO) {
    this.cacheStorage = cacheStorage;
    this.userDAO = userDAO;
    this.validator = new RequestParametersValidator();
  }

  public FullHttpResponse handleRequest(String method, String uri, String content) {
    Map<String, String> params = parseUri(uri);

    try {
      switch (method) {
        case "POST":
          return handleCreateStorage(params);
        case "GET":
          return handleGetStats(params);
        default:
          return createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
      }
    } catch (Exception e) {
      System.err.println("Error processing storage request: " + e.getMessage());
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Server error: " + e.getMessage());
    }
  }

  private FullHttpResponse handleCreateStorage(Map<String, String> params) {
    String login = params.get("login");
    String password = params.get("password");

    if (login == null || password == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: login or password");
    }

    if (!authenticateUser(login, password)) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid credentials");
    }

    // В новой архитектуре CacheStorage создается один раз, поэтому генерируем токен для информации
    String storageToken = UUID.randomUUID().toString();
    return createResponse(HttpResponseStatus.OK, "Storage initialized. Token: " + storageToken + "\nNote: In new architecture, CacheStorage is singleton with unified storage.");
  }

  private FullHttpResponse handleGetStats(Map<String, String> params) {
    String login = params.get("login");
    String password = params.get("password");

    if (login == null || password == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: login or password");
    }

    if (!authenticateUser(login, password)) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid credentials");
    }

    Map<String, Integer> stats = cacheStorage.getStats();
    StringBuilder statsBuilder = new StringBuilder();
    statsBuilder.append("Cache Storage Statistics:\n\n");

    if (stats.isEmpty()) {
      statsBuilder.append("No active caches\n");
    } else {
      stats.forEach((type, size) -> {
        statsBuilder.append("Type: ").append(type)
            .append(" | Entries: ").append(size)
            .append("\n");
      });
    }

    return createResponse(HttpResponseStatus.OK, statsBuilder.toString());
  }

  private boolean authenticateUser(String login, String password) {
    User user = userDAO.findByUsername(login);
    return user != null && user.getPasswordPlain().equals(password);
  }
}