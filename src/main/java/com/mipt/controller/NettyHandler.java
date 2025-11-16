package com.mipt.controller;

import com.mipt.cache.Cache;
import com.mipt.service.CacheStorageService;
import com.mipt.userstorage.dao.*;
import com.mipt.userstorage.model.User;
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
  private final UserDAO userDAO;
  private final PermissionDAO permissionDAO;
  private final CacheStorageDAO cacheStorageDAO;
  private final RequestParametersValidator validator;

  public NettyHandler(CacheStorageService cacheService,
      UserDAO userDAO,
      PermissionDAO permissionDAO,
      CacheStorageDAO cacheStorageDAO) {
    this.cacheService = cacheService;
    this.userDAO = userDAO;
    this.permissionDAO = permissionDAO;
    this.cacheStorageDAO = cacheStorageDAO;
    this.validator = new RequestParametersValidator();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    String method = request.method().name();
    String uri = request.uri();

    System.out.println("Received " + method + " request: " + uri);

    // Обработка корневого пути
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
    // Валидация запроса
    RequestParametersValidator.ValidationResult validation = validator.validateRequest(method, uri);
    if (!validation.getValid()) {
      sendBadRequest(ctx, validation.getErrorsAsString()); // Используем getErrorsAsString() вместо getErrors()
      return;
    }

    // Парсим параметры из URI
    Map<String, String> params = parseUri(uri);
    String storageToken = params.get("storage_token");
    String key = params.get("key");
    String type = params.get("type");
    String login = params.get("login");
    String password = params.get("password");

    FullHttpResponse response;

    try {
      // Проверка аутентификации
      User user = userDAO.findByUsername(login);
      if (user == null || !user.getPasswordPlain().equals(password)) {
        response = createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid credentials");
        ctx.writeAndFlush(response);
        return;
      }

      switch (method) {
        case "GET":
          response = handleCacheGet(storageToken, type, key);
          break;
        case "POST":
          response = handleCachePost(storageToken, type, key, content);
          break;
        case "PUT":
          response = handleCachePut(storageToken, type, key, content);
          break;
        case "DELETE":
          response = handleCacheDelete(storageToken, type, key);
          break;
        default:
          response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
          break;
      }
    } catch (Exception e) {
      System.err.println("Error processing cache request: " + e.getMessage());
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Server error: " + e.getMessage());
    }

    ctx.writeAndFlush(response);
  }

  private void handleStorageRequest(ChannelHandlerContext ctx, String method, String uri, String content) {
    // Парсим параметры из URI
    Map<String, String> params = parseUri(uri);

    FullHttpResponse response;

    try {
      switch (method) {
        case "POST":
          // Создание нового хранилища
          response = handleCreateStorage(params);
          break;
        case "PUT":
          // Добавление пользователя в хранилище
          response = handleAddUserToStorage(params);
          break;
        default:
          response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
          break;
      }
    } catch (Exception e) {
      System.err.println("Error processing storage request: " + e.getMessage());
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Server error: " + e.getMessage());
    }

    ctx.writeAndFlush(response);
  }

  private FullHttpResponse handleCacheGet(String storageToken, String type, String key) {
    // Используем CacheStorageService вместо прямого доступа к кэшу
    com.mipt.cache.CacheResult result = cacheService.readData(storageToken, type, key);

    if (!result.isSuccess()) {
      return createResponse(HttpResponseStatus.NOT_FOUND, result.getMessage());
    }

    // Форматируем данные для ответа
    String responseData = formatDataForResponse(result.getData(), type);
    return createResponse(HttpResponseStatus.OK, responseData);
  }

  private FullHttpResponse handleCachePost(String storageToken, String type, String key, String value) {
    try {
      // Валидация данных и преобразование в Object
      Object processedValue = processDataForStorage(value, type);

      // Используем CacheStorageService для вставки
      com.mipt.cache.CacheResult result = cacheService.insertData(storageToken, type, key, processedValue.toString());

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
      }

      return createResponse(HttpResponseStatus.OK, result.getMessage());
    } catch (IllegalArgumentException e) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Invalid data format for type " + type + ": " + e.getMessage());
    } catch (Exception e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Error processing data: " + e.getMessage());
    }
  }


  private FullHttpResponse handleCachePut(String storageToken, String type, String key, String value) {
    try {
      // Валидация данных и преобразование в Object
      Object processedValue = processDataForStorage(value, type);

      // Используем CacheStorageService для обновления
      com.mipt.cache.CacheResult result = cacheService.updateData(storageToken, type, key, processedValue.toString());

      if (!result.isSuccess()) {
        return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
      }

      return createResponse(HttpResponseStatus.OK, result.getMessage());
    } catch (IllegalArgumentException e) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Invalid data format for type " + type + ": " + e.getMessage());
    } catch (Exception e) {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Error processing data: " + e.getMessage());
    }
  }

  private FullHttpResponse handleCacheDelete(String storageToken, String type, String key) {
    // Используем CacheStorageService для удаления
    com.mipt.cache.CacheResult result = cacheService.deleteData(storageToken, type, key);

    if (!result.isSuccess()) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, result.getMessage());
    }

    return createResponse(HttpResponseStatus.OK, result.getMessage());
  }

  private FullHttpResponse handleCreateStorage(Map<String, String> params) {
    String login = params.get("login");
    String password = params.get("password");

    // Проверка параметров
    if (login == null || password == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: login or password");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(login);
    if (user == null || !user.getPasswordPlain().equals(password)) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid credentials");
    }

    // Создание нового хранилища
    String storageToken = cacheService.createNewStorage();

    // Здесь должна быть логика сохранения информации о хранилище в БД
    // cacheStorageDAO.save(new CacheStorageEntity(storageToken, user.getId()));

    return createResponse(HttpResponseStatus.OK, "Storage created successfully. Token: " + storageToken);
  }

  private FullHttpResponse handleAddUserToStorage(Map<String, String> params) {
    String login = params.get("login");
    String password = params.get("password");
    String addedUser = params.get("addeduser");
    String role = params.get("role");
    String storageToken = params.get("storage_token");

    // Проверка параметров
    if (login == null || password == null || addedUser == null || role == null || storageToken == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Missing parameters: login, password, addeduser, role or storage_token");
    }

    // Проверка аутентификации
    User user = userDAO.findByUsername(login);
    if (user == null || !user.getPasswordPlain().equals(password)) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "Invalid credentials");
    }

    // Проверка существования добавляемого пользователя
    User userToAdd = userDAO.findByUsername(addedUser);
    if (userToAdd == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "User to add not found: " + addedUser);
    }

    // Проверка существования хранилища
    if (!cacheService.storageExists(storageToken)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Storage not found: " + storageToken);
    }

    // Проверка роли
    if (!UserRole.isValid(role)) {
      return createResponse(HttpResponseStatus.BAD_REQUEST,
          "Invalid role. Allowed: " + String.join(", ", UserRole.getAllValues()));
    }

    // Здесь должна быть логика добавления пользователя в хранилище с указанной ролью
    // permissionDAO.save(new PermissionEntity(userToAdd.getId(), storageToken, role));

    return createResponse(HttpResponseStatus.OK,
        "User " + addedUser + " added to storage with role: " + role);
  }

  // Вспомогательные методы для работы с типами данных

  private boolean validateDataByType(String data, String type) {
    if (data == null || data.trim().isEmpty()) {
      return false;
    }

    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return false;
    }

    switch (dataType) {
      case JSON:
        return isValidJSON(data);
      case BYTES:
        return isValidBase64(data);
      case STRING:
        return true; // Любая строка валидна
      default:
        return false;
    }
  }

  private boolean isValidJSON(String data) {
    try {
      // Простая проверка JSON - можно использовать библиотеку типа Jackson для более строгой проверки
      String trimmed = data.trim();
      return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
          (trimmed.startsWith("[") && trimmed.endsWith("]"));
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isValidBase64(String data) {
    try {
      // Проверка Base64
      java.util.Base64.getDecoder().decode(data);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private String processDataForStorage(String data, String type) {
    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return data;
    }

    switch (dataType) {
      case BYTES:
        // Для байтов проверяем, что это валидный Base64 и возвращаем как есть
        // CacheStorageService будет хранить как строку
        if (isValidBase64(data)) {
          return data;
        } else {
          throw new IllegalArgumentException("Invalid Base64 data");
        }
      case JSON:
        // Для JSON проверяем базовую структуру
        if (isValidJSON(data)) {
          return data;
        } else {
          throw new IllegalArgumentException("Invalid JSON data");
        }
      case STRING:
      default:
        return data;
    }
  }

  private String formatDataForResponse(Object data, String type) {
    DataType dataType = DataType.fromString(type);
    if (dataType == null) {
      return data != null ? data.toString() : "";
    }

    // Данные уже правильно обработаны при чтении из кэша
    return data != null ? data.toString() : "";
  }

  private void sendInfoPage(ChannelHandlerContext ctx) {
    String info = "Cache Storage HTTP Server\n\n" +
        "Available endpoints:\n" +
        "CACHE OPERATIONS:\n" +
        "GET    /cache?storage_token=TOKEN&key=KEY&type=TYPE&login=USERNAME&password=PASSWORD\n" +
        "POST   /cache?storage_token=TOKEN&key=KEY&type=TYPE&login=USERNAME&password=PASSWORD (with body)\n" +
        "PUT    /cache?storage_token=TOKEN&key=KEY&type=TYPE&login=USERNAME&password=PASSWORD (with body)\n" +
        "DELETE /cache?storage_token=TOKEN&key=KEY&type=TYPE&login=USERNAME&password=PASSWORD\n\n" +
        "STORAGE OPERATIONS:\n" +
        "POST   /storage?login=USERNAME&password=PASSWORD (create new storage)\n" +
        "PUT    /storage?login=USERNAME&password=PASSWORD&addeduser=USER&role=ROLE&storage_token=TOKEN (add user)\n\n" +
        "Data types: " + String.join(", ", DataType.getAllValues()) + "\n" +
        "User roles: " + String.join(", ", UserRole.getAllValues()) + "\n\n" +
        "Examples:\n" +
        "curl \"http://localhost:8080/cache?storage_token=abc123&key=test&type=string&login=admin&password=pass\"\n" +
        "curl -X POST -d 'value' \"http://localhost:8080/cache?storage_token=abc123&key=test&type=string&login=admin&password=pass\"";

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