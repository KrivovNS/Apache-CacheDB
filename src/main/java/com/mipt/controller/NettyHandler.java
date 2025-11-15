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

  public NettyHandler(CacheStorageService cacheService,
      UserDAO userDAO,
      PermissionDAO permissionDAO,
      CacheStorageDAO cacheStorageDAO) {
    this.cacheService = cacheService;
    this.userDAO = userDAO;
    this.permissionDAO = permissionDAO;
    this.cacheStorageDAO = cacheStorageDAO;
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
    } else {
      sendNotFound(ctx, "Endpoint not found: " + uri);
    }
  }

  private void handleCacheRequest(ChannelHandlerContext ctx, String method, String uri, String content) {
    // Парсим параметры из URI
    Map<String, String> params = parseUri(uri);
    String storageName = params.get("storage");
    String key = params.get("key");
    String username = params.get("user");

    FullHttpResponse response;

    try {
      switch (method) {
        case "GET":
          response = handleGet(storageName, key, username);
          break;
        case "PUT":
          response = handlePut(storageName, key, content, username);
          break;
        case "DELETE":
          response = handleDelete(storageName, key, username);
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

  private FullHttpResponse handleGet(String storageName, String key, String username) {
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
    if (cacheService.cacheExists(storageName)) {
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

  private FullHttpResponse handlePut(String storageName, String key, String value, String username) {
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
    if (cacheService.cacheExists(storageName)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache not found: " + storageName);
    }

    // Получаем кэш
    Cache cache = cacheService.getCache(storageName);

    // Сохраняем значение
    cache.put(key, value);

    return createResponse(HttpResponseStatus.OK, "Value stored successfully for key: " + key);
  }

  private FullHttpResponse handleDelete(String storageName, String key, String username) {
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
    if (cacheService.cacheExists(storageName)) {
      return createResponse(HttpResponseStatus.NOT_FOUND, "Cache not found: " + storageName);
    }

    // Получаем кэш
    Cache cache = cacheService.getCache(storageName);

    // Удаляем значение
    cache.remove(key);

    return createResponse(HttpResponseStatus.OK, "Key deleted successfully: " + key);
  }

  private void sendInfoPage(ChannelHandlerContext ctx) {
    String info = "Cache Storage HTTP Server\n\n" +
        "Available endpoints:\n" +
        "GET    /cache?storage=NAME&key=KEY&user=USERNAME\n" +
        "PUT    /cache?storage=NAME&key=KEY&user=USERNAME (with body)\n" +
        "DELETE /cache?storage=NAME&key=KEY&user=USERNAME\n\n" +
        "Example:\n" +
        "curl \"http://localhost:8080/cache?storage=session_cache&key=test&user=admin\"";

    FullHttpResponse response = createResponse(HttpResponseStatus.OK, info);
    ctx.writeAndFlush(response);
  }

  private void sendNoContent(ChannelHandlerContext ctx) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
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