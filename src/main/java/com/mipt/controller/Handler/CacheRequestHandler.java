package com.mipt.controller.Handler;

import com.mipt.cache.CacheResult;
import com.mipt.cache.CacheStorage;
import com.mipt.controller.DataType;
import com.mipt.controller.DataTypeProcessor;
import com.mipt.controller.RequestParametersValidator;
import com.mipt.controller.ValidationResult;
import com.mipt.userstorage.dao.UserDAO;
import com.mipt.userstorage.model.User;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;

public class CacheRequestHandler extends BaseNettyHandler {

  private final CacheStorage cacheStorage;
  private final UserDAO userDAO;
  private final RequestParametersValidator validator;
  private final DataTypeProcessor dataTypeProcessor;

  public CacheRequestHandler(CacheStorage cacheStorage, UserDAO userDAO) {
    this.cacheStorage = cacheStorage;
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
    String type = params.get("type");
    String key = params.get("key");
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
          return handleGet(type, key);
        case "POST":
          return handlePost(type, key, content);
        case "PUT":
          return handlePut(type, key, content);
        case "DELETE":
          return handleDelete(type, key);
        default:
          return createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
      }
    } catch (Exception e) {
      System.err.println("Error processing cache request: " + e.getMessage());
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Server error: " + e.getMessage());
    }
  }

  private FullHttpResponse handleGet(String type, String key) {
    if (!DataType.isValid(type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
    }

    CacheResult result = cacheStorage.read(type, key);
    if (!result.isSuccess()) {
      return createResponse(HttpResponseStatus.NOT_FOUND, result.getMessage());
    }

    String responseData = dataTypeProcessor.formatDataForResponse(result.getData(), type);
    return createResponse(HttpResponseStatus.OK, responseData);
  }

  private FullHttpResponse handlePost(String type, String key, String value) {
    return handleDataModification(type, key, value, true);
  }

  private FullHttpResponse handlePut(String type, String key, String value) {
    return handleDataModification(type, key, value, false);
  }

  private FullHttpResponse handleDataModification(String type, String key,
      String value, boolean checkExistence) {
    if (!DataType.isValid(type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
    }

    if (!dataTypeProcessor.validateDataByType(value, type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Invalid data format for type: " + type);
    }

    Object processedValue = dataTypeProcessor.processDataForStorage(value, type);
    CacheResult result;

    if (checkExistence) {
      result = cacheStorage.insert(type, key, processedValue);
    } else {
      result = cacheStorage.put(type, key, processedValue);
    }

    if (!result.isSuccess()) {
      HttpResponseStatus status = checkExistence && result.getMessage().contains("already exists")
          ? HttpResponseStatus.CONFLICT
          : HttpResponseStatus.BAD_REQUEST;
      return createResponse(status, result.getMessage());
    }

    String message = checkExistence ? "inserted" : "updated";
    return createResponse(HttpResponseStatus.OK, "Value " + message + " successfully for key: " + key);
  }

  private FullHttpResponse handleDelete(String type, String key) {
    if (!DataType.isValid(type)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data type. Allowed: " + String.join(", ", DataType.getAllValues()));
    }

    CacheResult result = cacheStorage.delete(type, key);
    if (!result.isSuccess()) {
      return createResponse(HttpResponseStatus.NOT_FOUND, result.getMessage());
    }

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