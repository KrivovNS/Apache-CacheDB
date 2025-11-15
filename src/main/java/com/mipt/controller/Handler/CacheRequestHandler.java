package com.mipt.controller.Handler;

import com.mipt.cache.Cache;
import com.mipt.controller.DataType.DataType;
import com.mipt.controller.DataType.DataTypeProcessor;
import com.mipt.controller.RequestParametersValidator;
import com.mipt.controller.ValidationResult;
import com.mipt.service.CacheStorageService;
import com.mipt.userstorage.dao.UserDAO;
import com.mipt.userstorage.model.User;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;

public class CacheRequestHandler extends BaseNettyHandler {

  private final CacheStorageService cacheService;
  private final UserDAO userDAO;
  private final RequestParametersValidator validator;
  private final DataTypeProcessor dataTypeProcessor;

  public CacheRequestHandler(CacheStorageService cacheService, UserDAO userDAO) {
    this.cacheService = cacheService;
    this.userDAO = userDAO;
    this.validator = new RequestParametersValidator();
    this.dataTypeProcessor = new DataTypeProcessor();
  }

  public FullHttpResponse handleRequest(String method, String uri, String content,
      ChannelHandlerContext ctx) {
    ValidationResult validation = validator.validateRequest(method, uri);
    if (!validation.getValid()) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, validation.getErrors());
    }

    Map<String, String> params = parseUri(uri);
    String storageToken = params.get("storage_token");
    String key = params.get("key");
    String type = params.get("type");
    String login = params.get("login");
    String password = params.get("password");

    if (!authenticateUser(login, password)) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid credentials");
    }
    if (("POST".equals(method) || "PUT".equals(method)) && (content == null || content.trim().isEmpty())) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Request body is required for " + method + " operations");
    }

    try {
      switch (method) {
        case "GET":
          return handleGet(storageToken, type, key);
        case "POST":
          return handlePost(storageToken, type, key, content);
        case "PUT":
          return handlePut(storageToken, type, key, content);
        case "DELETE":
          return handleDelete(storageToken, type, key);
        default:
          return createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
      }
    } catch (Exception e) {
      System.err.println("Error processing cache request: " + e.getMessage());
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Server error: " + e.getMessage());
    }
  }

  private FullHttpResponse handleGet(String storageToken, String type, String key) {
    if (!cacheService.storageExists(storageToken)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Storage not found: " + storageToken);
    }

    if (!DataType.isValid(type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
    }

    Cache cache = cacheService.getCache(storageToken, type);
    if (cache == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache for type " + type + " not found");
    }

    Object value = cache.get(key);
    if (value == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Key not found: " + key);
    }

    String responseData = dataTypeProcessor.formatDataForResponse(value, type);
    return createResponse(HttpResponseStatus.OK, responseData);
  }

  private FullHttpResponse handlePost(String storageToken, String type, String key, String value) {
    return handleDataModification(storageToken, type, key, value, true);
  }

  private FullHttpResponse handlePut(String storageToken, String type, String key, String value) {
    return handleDataModification(storageToken, type, key, value, false);
  }

  private FullHttpResponse handleDataModification(String storageToken, String type, String key,
      String value, boolean checkExistence) {
    if (!cacheService.storageExists(storageToken)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Storage not found: " + storageToken);
    }

    if (!DataType.isValid(type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
    }

    if (!dataTypeProcessor.validateDataByType(value, type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Invalid data format for type: " + type);
    }

    Cache cache = cacheService.getCache(storageToken, type);
    if (cache == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache for type " + type + " not found");
    }

    if (checkExistence && cache.containsKey(key)) {
      return createResponse(HttpResponseStatus.CONFLICT, "Key already exists: " + key);
    }

    Object processedValue = dataTypeProcessor.processDataForStorage(value, type);
    cache.put(key, processedValue);

    String message = checkExistence ? "inserted" : "updated";
    return createResponse(HttpResponseStatus.OK, "Value " + message + " successfully for key: " + key);
  }

  private FullHttpResponse handleDelete(String storageToken, String type, String key) {
    if (!cacheService.storageExists(storageToken)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Storage not found: " + storageToken);
    }

    if (!DataType.isValid(type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
    }

    Cache cache = cacheService.getCache(storageToken, type);
    if (cache == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache for type " + type + " not found");
    }

    if (!cache.containsKey(key)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Key not found: " + key);
    }

    cache.remove(key);
    return createResponse(HttpResponseStatus.OK, "Key deleted successfully: " + key);
  }

  private boolean authenticateUser(String login, String password) {
    if (login == null || password == null) {
      return false;
    }
    User user = userDAO.findByUsername(login);
    return user != null && user.getPasswordPlain().equals(password);
  }
}