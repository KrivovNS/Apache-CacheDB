package com.mipt.controller.Handler;

import com.mipt.controller.RequestParametersValidator;
import com.mipt.controller.UserRole;
import com.mipt.service.CacheStorageService;
import com.mipt.userstorage.dao.UserDAO;
import com.mipt.userstorage.model.User;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;

public class StorageRequestHandler extends BaseNettyHandler {

  private final CacheStorageService cacheService;
  private final UserDAO userDAO;
  private final RequestParametersValidator validator;

  public StorageRequestHandler(CacheStorageService cacheService, UserDAO userDAO) {
    this.cacheService = cacheService;
    this.userDAO = userDAO;
    this.validator = new RequestParametersValidator();
  }

  public FullHttpResponse handleRequest(String method, String uri, String content) {
    Map<String, String> params = parseUri(uri);

    try {
      switch (method) {
        case "POST":
          return handleCreateStorage(params);
        case "PUT":
          return handleAddUserToStorage(params);
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

    String storageToken = cacheService.createNewStorage();
    return createResponse(HttpResponseStatus.OK, "Storage created successfully. Token: " + storageToken);
  }

  private FullHttpResponse handleAddUserToStorage(Map<String, String> params) {
    String login = params.get("login");
    String password = params.get("password");
    String addedUser = params.get("addeduser");
    String role = params.get("role");
    String storageToken = params.get("storage_token");

    if (login == null || password == null || addedUser == null || role == null || storageToken == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing parameters: login, password, addeduser, role or storage_token");
    }

    if (!authenticateUser(login, password)) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid credentials");
    }

    User userToAdd = userDAO.findByUsername(addedUser);
    if (userToAdd == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "User to add not found: " + addedUser);
    }

    if (!cacheService.storageExists(storageToken)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Storage not found: " + storageToken);
    }

    if (!UserRole.isValid(role)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid role. Allowed: " + String.join(", ", UserRole.getAllValues()));
    }

    return createResponse(HttpResponseStatus.OK,
        "User " + addedUser + " added to storage with role: " + role);
  }

  private boolean authenticateUser(String login, String password) {
    User user = userDAO.findByUsername(login);
    return user != null && user.getPasswordPlain().equals(password);
  }
}