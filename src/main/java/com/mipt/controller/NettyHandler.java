package com.mipt.controller;

import com.mipt.cache.CacheResult;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.database.dao.*;
import com.mipt.model.User;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class NettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  private final CacheStorageService cacheService;
  private final SessionService sessionService;
  private final UserDAO userDAO;
  private final CacheStorageDAO cacheStorageDAO;
  private final RequestParametersValidator validator;

  public NettyHandler(CacheStorageService cacheService,
      SessionService sessionService,
      UserDAO userDAO,
      CacheStorageDAO cacheStorageDAO) {
    this.cacheService = cacheService;
    this.sessionService = sessionService;
    this.userDAO = userDAO;
    this.cacheStorageDAO = cacheStorageDAO;
    this.validator = new RequestParametersValidator();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    String method = request.method().name();
    String uri = request.uri();

    System.out.println("Received " + method + " request: " + uri);

    if ("/".equals(uri)) {
      sendInfoPage(ctx);
      return;
    }

    String content = request.content().toString(CharsetUtil.UTF_8);

    // Обработка запросов к кэшу
    if (uri.startsWith("/cache")) {
      handleCacheRequest(ctx, method, uri, content);
    }
    // Обработка запросов к хранилищу
    else if (uri.startsWith("/storage")) {
      handleStorageRequest(ctx, method, uri, content);
    } else {
      sendNotFound(ctx, "Endpoint not found: " + uri);
    }
  }

  private void handleCacheRequest(ChannelHandlerContext ctx, String method, String uri, String content) {
    ValidationResult validation = validator.validateRequest(method, uri);
    if (!validation.getValid()) {
      sendBadRequest(ctx, validation.getErrors());
      return;
    }

    // Парсим параметры из URI
    Map<String, String> params = parseUri(uri);
    String sessionToken = params.get("session_token");
    String key = params.get("key");
    String type = params.get("type");

    FullHttpResponse response;

    try {
      // Проверяем валидность сессии
      if (!sessionService.isSessionValid(sessionToken)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid or expired session");
        ctx.writeAndFlush(response);
        return;
      }

      DataType dataType = DataType.fromValue(type);
      if (dataType == null) {
        response = createResponse(HttpResponseStatus.BAD_REQUEST, "Invalid data type: " + type);
        ctx.writeAndFlush(response);
        return;
      }

      response = switch (method) {
        case "GET" -> handleCacheGet(sessionToken, dataType, key);
        case "POST" -> handleCachePost(sessionToken, dataType, key, content);
        case "PUT" -> handleCachePut(sessionToken, dataType, key, content);
        case "DELETE" -> handleCacheDelete(sessionToken, dataType, key);
        default -> createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
      };
    } catch (Exception e) {
      System.err.println("Error processing cache request: " + e.getMessage());
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Server error: " + e.getMessage());
    }

    ctx.writeAndFlush(response);
  }

  private void handleStorageRequest(ChannelHandlerContext ctx, String method, String uri, String content) {
    Map<String, String> params = parseUri(uri);

    FullHttpResponse response;

    try {
      if ("POST".equals(method)) {
        response = handleCreateStorage(params);
      } else {
        response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
      }
    } catch (Exception e) {
      System.err.println("Error processing storage request: " + e.getMessage());
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Server error: " + e.getMessage());
    }

    ctx.writeAndFlush(response);
  }

  private FullHttpResponse handleCacheGet(String sessionToken, DataType dataType, String key) {
    try {
      CacheResult result = cacheService.readData(sessionToken, dataType, key);

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.NOT_FOUND, result.getMessage());
      }

      String responseData = DataTypeValidator.formatDataForResponse(result.getData(), dataType.getValue());
      return createResponse(HttpResponseStatus.OK, responseData);

    } catch (IllegalArgumentException e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error formatting response: " + e.getMessage());
    } catch (Exception e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error reading data: " + e.getMessage());
    }
  }

  private FullHttpResponse handleCachePost(String sessionToken, DataType dataType, String key, String value) {
    try {
      Object processedValue = DataTypeValidator.processDataForStorage(value, dataType.getValue());

      CacheResult result = cacheService.insertData(sessionToken, dataType, key, processedValue);

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
      }

      return createResponse(HttpResponseStatus.OK, result.getMessage());
    } catch (IllegalArgumentException e) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data format for type " + dataType + ": " + e.getMessage());
    } catch (Exception e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error processing data: " + e.getMessage());
    }
  }

  private FullHttpResponse handleCachePut(String sessionToken, DataType dataType, String key, String value) {
    try {
      Object processedValue = DataTypeValidator.processDataForStorage(value, dataType.getValue());

      CacheResult result = cacheService.updateData(sessionToken, dataType, key, processedValue);

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
      }

      return createResponse(HttpResponseStatus.OK, result.getMessage());
    } catch (IllegalArgumentException e) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid data format for type " + dataType + ": " + e.getMessage());
    } catch (Exception e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Error processing data: " + e.getMessage());
    }
  }

  private FullHttpResponse handleCacheDelete(String sessionToken, DataType dataType, String key) {
    CacheResult result = cacheService.deleteData(sessionToken, dataType, key);

    if (!result.isSuccess()) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
    }

    return createResponse(HttpResponseStatus.OK, result.getMessage());
  }

  private FullHttpResponse handleCreateStorage(Map<String, String> params) {
    String login = params.get("login");
    String password = params.get("password");

    if (login == null || password == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing parameters: login or password");
    }

    User user = userDAO.findByUsername(login);

    if (user == null) {
      // Создаем нового пользователя и хранилище
      String sessionToken = sessionService.createNewSession();

      // Получаем storageToken из сессии
      String storageToken = sessionService.getStorageBySession(sessionToken)
          .map(storage -> storage.getStorageToken())
          .orElse(null);

      if (storageToken != null) {
        // Создаем пользователя с привязкой к хранилищу
        User createdUser = userDAO.createUser(login, password, storageToken);
        if (createdUser != null) {
          return createResponse(HttpResponseStatus.OK,
              "Storage created successfully. Session token: " + sessionToken);
        } else {
          return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to create user");
        }
      } else {
        return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to create storage");
      }
    } else {
      // Проверяем пароль
      if (!user.getPassword().equals(password)) {
        return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid password");
      }

      // Получаем хранилище пользователя
      String storageToken = user.getStorageToken();
      if (storageToken == null) {
        return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "User has no storage assigned");
      }

      // Создаем сессию для хранилища
      String sessionToken = sessionService.createSessionForStorage(storageToken);
      return createResponse(HttpResponseStatus.OK,
          "Session created. Session token: " + sessionToken);
    }
  }

  private void sendInfoPage(ChannelHandlerContext ctx) {
    String info = "Cache Storage HTTP Server\n\n" +
        "Available endpoints:\n\n" +
        "CACHE OPERATIONS:\n" +
        "GET    /cache?session_token=TOKEN&key=KEY&type=TYPE\n" +
        "POST   /cache?session_token=TOKEN&key=KEY&type=TYPE (with body - insert)\n" +
        "PUT    /cache?session_token=TOKEN&key=KEY&type=TYPE (with body - update)\n" +
        "DELETE /cache?session_token=TOKEN&key=KEY&type=TYPE\n\n" +
        "STORAGE OPERATIONS:\n" +
        "POST   /storage?login=USERNAME&password=PASSWORD (create storage/get session)\n\n" +
        "Data types: " + String.join(", ", DataType.getAllValues()) + "\n\n" +
        "Workflow:\n" +
        "1. First request to /storage with login/password - creates storage and returns session_token\n" +
        "2. Use session_token for all cache operations\n" +
        "3. Session expires after 24 hours of inactivity\n\n" +
        "Examples:\n" +
        "Create storage: curl -X POST \"http://localhost:8080/storage?login=admin&password=pass\"\n" +
        "Read cache: curl \"http://localhost:8080/cache?session_token=xyz789&key=test&type=string\"\n" +
        "Insert data: curl -X POST -d 'value' \"http://localhost:8080/cache?session_token=xyz789&key=test&type=string\"";

    FullHttpResponse response = createResponse(HttpResponseStatus.OK, info);
    ctx.writeAndFlush(response);
  }

  private void sendBadRequest(ChannelHandlerContext ctx, String message) {
    FullHttpResponse response = createResponse(HttpResponseStatus.BAD_REQUEST, message);
    ctx.writeAndFlush(response);
  }

  private void sendNotFound(ChannelHandlerContext ctx, String message) {
    FullHttpResponse response = createResponse(HttpResponseStatus.NOT_FOUND, message);
    ctx.writeAndFlush(response);
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

  private FullHttpResponse createResponse(HttpResponseStatus status, String content) {
    ByteBuf buffer = Unpooled.copiedBuffer(content, StandardCharsets.UTF_8);
    FullHttpResponse response = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, status, buffer
    );

    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

    return response;
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    System.err.println("Error processing request: " + cause.getMessage());
    cause.printStackTrace();

    FullHttpResponse errorResponse = createResponse(
        HttpResponseStatus.INTERNAL_SERVER_ERROR,
        "Server error: " + cause.getMessage()
    );
    ctx.writeAndFlush(errorResponse);
  }
}