package com.mipt.controller;

import com.mipt.cache.Cache;
import com.mipt.service.CacheStorageService;
import com.mipt.userstorage.dao.*;
import com.mipt.userstorage.model.User;
import com.mipt.cache.FileDistributor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Основной обработчик HTTP запросов для системы кэш-хранилищ
 * Обрабатывает операции с кэшем, файлами и статистикой
 */
public class NettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  // Сервисы и DAO объекты для работы с данными
  private final CacheStorageService cacheService;
  private final UserDAO userDAO;
  private final PermissionDAO permissionDAO;
  private final CacheStorageDAO cacheStorageDAO;
  private final FileDistributor fileDistributor;

  public NettyHandler(CacheStorageService cacheService,
                      UserDAO userDAO,
                      PermissionDAO permissionDAO,
                      CacheStorageDAO cacheStorageDAO,
                      FileDistributor fileDistributor) {
    this.cacheService = cacheService;
    this.userDAO = userDAO;
    this.permissionDAO = permissionDAO;
    this.cacheStorageDAO = cacheStorageDAO;
    this.fileDistributor = fileDistributor;
  }

  // ========== ОСНОВНОЙ МЕТОД ОБРАБОТКИ ЗАПРОСОВ ==========

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    String method = request.method().name();
    String uri = request.uri();

    String content = request.content().toString(CharsetUtil.UTF_8);

    System.out.println("Received " + method + " request: " + uri);

    // Обработка корневого пути
    if ("/".equals(uri)) {
      sendInfoPage(ctx);
      return;
    }


    if (uri.startsWith("/cache")) {
      handleCacheRequest(ctx, method, uri, content);
    }

    else if (uri.startsWith("/file")) {
      handleFileRequest(ctx, method, uri, content);
    }

    else if (uri.startsWith("/stats")) {
      handleStatsRequest(ctx, uri);
    }
    else {
      sendNotFound(ctx, "Endpoint not found: " + uri);
    }
  }

  // ========== ОБРАБОТКА ЗАПРОСОВ К КЭШУ ==========

  /**
   * Обработка всех запросов связанных с кэш-хранилищами
   */
  private void handleCacheRequest(ChannelHandlerContext ctx, String method, String uri, String content) {
    // Парсим параметры из URI
    Map<String, String> params = parseUri(uri);
    String storageName = params.get("storage");
    String key = params.get("key");
    String username = params.get("user");
    // Параметр filename больше не используется в cache запросах

    FullHttpResponse response;

    try {
      switch (method) {
        case "GET":
          response = handleCacheGet(storageName, key, username);
          break;
        case "PUT":
          response = handleCachePut(storageName, key, content, username);
          break;
        case "DELETE":
          response = handleCacheDelete(storageName, key, username);
          break;
        case "POST":
          response = handleCacheCreate(storageName, username, params);
          break;
        default:
          response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
          break;
      }
    } catch (Exception e) {
      System.err.println("Error processing request: " + e.getMessage());
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Server error: " + e.getMessage());
    }

    ctx.writeAndFlush(response);
  }

  /**
   * GET /cache?storage=NAME&key=KEY&user=USERNAME
   * Получить значение из кэша
   */
  private FullHttpResponse handleCacheGet(String storageName, String key, String username) {

    // Проверка параметров
    if (storageName == null || key == null || username == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: storage, key or user");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
    }

    // Проверка существования хранилища
    if (!cacheService.cacheExists(storageName)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache not found: " + storageName);
    }

    // Получаем кэш
    Cache cache = cacheService.getCache(storageName);

    // Получаем значение
    Object value = cache.get(key);
    if (value == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Key not found: " + key);
    }

    return createResponse(HttpResponseStatus.OK, "Value: " + value.toString());
  }

  /**
   * PUT /cache?storage=NAME&key=KEY&user=USERNAME
   * Сохранить значение в кэш
   */
  private FullHttpResponse handleCachePut(String storageName, String key, String value, String username) {

    // Проверка параметров
    if (storageName == null || key == null || username == null || value == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: storage, key, user or value");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
    }

    // Проверка существования хранилища
    if (!cacheService.cacheExists(storageName)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache not found: " + storageName);
    }

    // Получаем кэш
    Cache cache = cacheService.getCache(storageName);

    // Сохраняем значение
    cache.put(key, value);

    return createResponse(HttpResponseStatus.OK, "Value stored successfully for key: " + key);
  }

  /**
   * DELETE /cache?storage=NAME&key=KEY&user=USERNAME
   * Удалить значение из кэша
   */
  private FullHttpResponse handleCacheDelete(String storageName, String key, String username) {
    // ПЕРЕИМЕНОВАНО: было handleDelete, стало handleCacheDelete для ясности

    // Проверка параметров
    if (storageName == null || key == null || username == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: storage, key or user");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
    }

    // Проверка существования хранилища
    if (!cacheService.cacheExists(storageName)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache not found: " + storageName);
    }

    // Получаем кэш
    Cache cache = cacheService.getCache(storageName);

    // Удаляем значение
    cache.remove(key);

    return createResponse(HttpResponseStatus.OK, "Key deleted successfully: " + key);
  }

  /**
   * POST /cache?storage=NAME&user=USERNAME&type=TYPE&max_size=SIZE
   * Создать новое кэш-хранилище
   * ДОБАВЛЕН НОВЫЙ МЕТОД
   */
  private FullHttpResponse handleCacheCreate(String storageName, String username, Map<String, String> params) {
    if (storageName == null || username == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: storage and user");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
    }

    String cacheType = params.getOrDefault("type", "CONCURRENT");
    Integer maxSize = parseInteger(params.get("max_size"));

    // Создание хранилища
    boolean created = cacheService.createCacheStorage(storageName, cacheType, maxSize);
    if (created) {
      return createResponse(HttpResponseStatus.CREATED,
          "Cache storage created: " + storageName + " (type: " + cacheType + ")");
    } else {
      return createResponse(HttpResponseStatus.CONFLICT,
          "Cache storage already exists: " + storageName);
    }
  }

  // ========== ОБРАБОТКА ЗАПРОСОВ К ФАЙЛАМ ==========

  /**
   * Обработка всех запросов связанных с файлами
   * ДОБАВЛЕН НОВЫЙ МЕТОД
   */
  private void handleFileRequest(ChannelHandlerContext ctx, String method, String uri, String content) {
    Map<String, String> params = parseUri(uri);
    String key = params.get("key");
    String username = params.get("user");
    String filename = params.get("filename");

    FullHttpResponse response;

    try {
      switch (method) {
        case "GET":
          response = handleFileGet(key, filename, username);
          break;
        case "PUT":
          response = handleFileUpload(key, filename, content, username);
          break;
        case "DELETE":
          response = handleFileDelete(key, filename, username);
          break;
        default:
          response = createResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed for file operations");
          break;
      }
    } catch (Exception e) {
      System.err.println("Error processing file request: " + e.getMessage());
      response = createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Server error: " + e.getMessage());
    }

    ctx.writeAndFlush(response);
  }

  /**
   * GET /file?key=KEY&filename=NAME&user=USERNAME
   * Получить файл из хранилища
   * ПЕРЕМЕЩЕН: был отдельным методом, теперь в структурированной секции
   */
  private FullHttpResponse handleFileGet(String key, String filename, String username) {
    // Проверка параметров
    if (key == null || filename == null || username == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: key, filename or user");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
    }

    // Получаем файл через FileDistributor
    Object fileContent = fileDistributor.getFile(key, filename);
    if (fileContent == null) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "File not found with key: " + key);
    }

    return createResponse(HttpResponseStatus.OK, "File content: " + fileContent.toString());
  }

  /**
   * PUT /file?key=KEY&filename=NAME&user=USERNAME
   * Сохранить файл в хранилище
   * ПЕРЕМЕЩЕН: был отдельным методом, теперь в структурированной секции
   */
  private FullHttpResponse handleFileUpload(String key, String filename, String content, String username) {
    // Проверка параметров
    if (key == null || filename == null || username == null || content == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: key, filename, user or content");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
    }

    // Используем FileDistributor для сохранения файла
    byte[] fileContent = content.getBytes(StandardCharsets.UTF_8);
    boolean success = fileDistributor.storeFile(key, filename, fileContent);

    if (success) {
      return createResponse(HttpResponseStatus.OK,
          "File '" + filename + "' stored successfully with key: " + key);
    } else {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "Failed to store file: " + filename);
    }
  }

  /**
   * DELETE /file?key=KEY&filename=NAME&user=USERNAME
   * Удалить файл из хранилища
   * ПЕРЕМЕЩЕН: был отдельным методом, теперь в структурированной секции
   */
  private FullHttpResponse handleFileDelete(String key, String filename, String username) {
    // Проверка параметров
    if (key == null || filename == null || username == null) {
      return createResponse(HttpResponseStatus.BAD_REQUEST, "Missing parameters: key, filename or user");
    }

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      return createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
    }

    // Удаляем файл через FileDistributor
    boolean success = fileDistributor.deleteFile(key, filename);

    if (success) {
      return createResponse(HttpResponseStatus.OK, "File deleted successfully: " + filename);
    } else {
      return createResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to delete file: " + filename);
    }
  }

  // ========== ОБРАБОТКА СТАТИСТИКИ ==========

  /**
   * Обработка запросов статистики
   * УЛУЧШЕНО: добавлена проверка метода
   */
  private void handleStatsRequest(ChannelHandlerContext ctx, String uri) {
    Map<String, String> params = parseUri(uri);
    String username = params.get("user");

    // Проверка пользователя
    User user = userDAO.findByUsername(username);
    if (user == null) {
      FullHttpResponse response = createResponse(HttpResponseStatus.UNAUTHORIZED, "User not found: " + username);
      ctx.writeAndFlush(response);
      return;
    }

    String stats = fileDistributor.getDistributionStats();
    FullHttpResponse response = createResponse(HttpResponseStatus.OK, stats);
    ctx.writeAndFlush(response);
  }

  // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========

  /**
   * Отправка информационной страницы
   * УЛУЧШЕНО: расширена информация о доступных endpoint'ах
   */
  private void sendInfoPage(ChannelHandlerContext ctx) {
    String info = "=== Cache Storage HTTP Server ===\n\n" +
        "AVAILABLE ENDPOINTS:\n\n" +

        "CACHE OPERATIONS:\n" +
        "  GET    /cache?storage=NAME&key=KEY&user=USERNAME\n" +
        "  PUT    /cache?storage=NAME&key=KEY&user=USERNAME\n" +
        "  DELETE /cache?storage=NAME&key=KEY&user=USERNAME\n" +
        "  POST   /cache?storage=NAME&user=USERNAME&type=TYPE&max_size=SIZE\n\n" +

        "FILE OPERATIONS:\n" +
        "  GET    /file?key=KEY&filename=NAME&user=USERNAME\n" +
        "  PUT    /file?key=KEY&filename=NAME&user=USERNAME\n" +
        "  DELETE /file?key=KEY&filename=NAME&user=USERNAME\n\n" +

        "STATISTICS:\n" +
        "  GET    /stats?user=USERNAME\n\n" +

        "EXAMPLES:\n" +
        "  curl \"http://localhost:8080/cache?storage=test&key=data&user=admin\"\n" +
        "  curl -X PUT \"http://localhost:8080/file?key=doc1&filename=readme.txt&user=admin\" -d \"Content\"\n" +
        "  curl \"http://localhost:8080/stats?user=admin\"\n\n" +

        "SUPPORTED CACHE TYPES: SIMPLE, CONCURRENT, LRU\n" +
        "SUPPORTED FILE TYPES: .txt, .json, .jpg, .png, .pdf, etc.";

    FullHttpResponse response = createResponse(HttpResponseStatus.OK, info);
    ctx.writeAndFlush(response);
  }

  /**
   * Отправка ошибки 404
   */
  private void sendNotFound(ChannelHandlerContext ctx, String message) {
    FullHttpResponse response = createResponse(HttpResponseStatus.NOT_FOUND, message);
    ctx.writeAndFlush(response);
  }

  /**
   * УДАЛЕН МЕТОД: sendNoContent - не используется в коде
   * private void sendNoContent(ChannelHandlerContext ctx) { ... }
   */

  /**
   * Парсинг параметров из URI
   */
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

  /**
   * Создание HTTP ответа
   */
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

  /**
   * Вспомогательный метод для парсинга целых чисел
   * ДОБАВЛЕН НОВЫЙ МЕТОД
   */
  private Integer parseInteger(String value) {
    if (value == null) return null;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ========== ОБРАБОТКА ИСКЛЮЧЕНИЙ ==========

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