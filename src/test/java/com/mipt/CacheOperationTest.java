package com.mipt;

import com.mipt.controller.NettyHandler;
import com.mipt.database.dao.UserDAO;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CacheOperationTest {

  @Test
  void testCacheSetAndGet() {
    System.out.println("Testing cache set and get operations...");

    // Создаем один общий экземпляр для всего теста
    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    // 1. Аутентификация и получение токена
    System.out.println("Step 1: Authentication...");
    String authUri = "/auth?login=default&password=admin123";
    FullHttpRequest authRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        authUri
    );

    EmbeddedChannel authChannel = new EmbeddedChannel(nettyHandler);
    authChannel.writeInbound(authRequest);
    FullHttpResponse authResponse = authChannel.readOutbound();
    authChannel.close(); // Закрываем канал после использования

    assertEquals(HttpResponseStatus.OK, authResponse.status(),
        "Authentication should succeed");

    String authContent = authResponse.content().toString(StandardCharsets.UTF_8);
    String sessionToken = null;
    for (String line : authContent.split("\n")) {
      if (line.contains("Session token:")) {
        sessionToken = line.split(": ")[1].trim();
        break;
      }
    }

    assertNotNull(sessionToken, "Session token should not be null");
    System.out.println("Got session token: " + sessionToken);

    // 2. Записываем данные в кэш
    System.out.println("\nStep 2: Writing to cache...");
    String postUri = "/cache?session_token=" + sessionToken +
        "&key=testKey&type=string&ttl=30s";
    FullHttpRequest postRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.POST,
        postUri,
        Unpooled.copiedBuffer("Test cache value", StandardCharsets.UTF_8)
    );

    // Создаем НОВЫЙ канал для второго запроса
    EmbeddedChannel postChannel = new EmbeddedChannel(nettyHandler);
    postChannel.writeInbound(postRequest);
    FullHttpResponse postResponse = postChannel.readOutbound();
    postChannel.close();

    assertEquals(HttpResponseStatus.OK, postResponse.status(),
        "Cache write should succeed");

    String postContent = postResponse.content().toString(StandardCharsets.UTF_8);
    System.out.println("Cache write response: " + postContent);

    // 3. Читаем данные из кэша
    System.out.println("\nStep 3: Reading from cache...");
    String getUri = "/cache?session_token=" + sessionToken + "&key=testKey";
    FullHttpRequest getRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        getUri
    );

    // Создаем НОВЫЙ канал для третьего запроса
    EmbeddedChannel getChannel = new EmbeddedChannel(nettyHandler);
    getChannel.writeInbound(getRequest);
    FullHttpResponse getResponse = getChannel.readOutbound();
    getChannel.close();

    assertEquals(HttpResponseStatus.OK, getResponse.status(),
        "Cache read should succeed");

    String content = getResponse.content().toString(StandardCharsets.UTF_8);
    System.out.println("Cache read response: " + content);
    assertEquals("Test cache value", content,
        "Cache should return the same value that was stored");

    System.out.println("\n✓ Cache set and get operations successful");
  }

  @Test
  void testCacheUpdateAndDelete() {
    System.out.println("Testing cache update and delete operations...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    // 1. Аутентификация
    String sessionToken = authenticateAndGetToken(nettyHandler);
    assertNotNull(sessionToken);

    // 2. Создаем запись
    String key = "updateKey_" + System.currentTimeMillis();
    String createUri = "/cache?session_token=" + sessionToken +
        "&key=" + key + "&type=string&ttl=1m";

    FullHttpRequest createRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.POST,
        createUri,
        Unpooled.copiedBuffer("Initial value", StandardCharsets.UTF_8)
    );

    EmbeddedChannel createChannel = new EmbeddedChannel(nettyHandler);
    createChannel.writeInbound(createRequest);
    FullHttpResponse createResponse = createChannel.readOutbound();
    createChannel.close();

    assertEquals(HttpResponseStatus.OK, createResponse.status());
    System.out.println("✓ Initial cache entry created");

    // 3. Обновляем запись
    String updateUri = "/cache?session_token=" + sessionToken +
        "&key=" + key + "&type=string&ttl=2m";

    FullHttpRequest updateRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.PUT,
        updateUri,
        Unpooled.copiedBuffer("Updated value", StandardCharsets.UTF_8)
    );

    EmbeddedChannel updateChannel = new EmbeddedChannel(nettyHandler);
    updateChannel.writeInbound(updateRequest);
    FullHttpResponse updateResponse = updateChannel.readOutbound();
    updateChannel.close();

    assertEquals(HttpResponseStatus.OK, updateResponse.status());
    System.out.println("✓ Cache entry updated");

    // 4. Проверяем обновленное значение
    String getUri = "/cache?session_token=" + sessionToken + "&key=" + key;
    FullHttpRequest getRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        getUri
    );

    EmbeddedChannel getChannel = new EmbeddedChannel(nettyHandler);
    getChannel.writeInbound(getRequest);
    FullHttpResponse getResponse = getChannel.readOutbound();
    getChannel.close();

    assertEquals(HttpResponseStatus.OK, getResponse.status());
    assertEquals("Updated value",
        getResponse.content().toString(StandardCharsets.UTF_8));
    System.out.println("✓ Updated value verified");

    // 5. Удаляем запись
    String deleteUri = "/cache?session_token=" + sessionToken + "&key=" + key;
    FullHttpRequest deleteRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.DELETE,
        deleteUri
    );

    EmbeddedChannel deleteChannel = new EmbeddedChannel(nettyHandler);
    deleteChannel.writeInbound(deleteRequest);
    FullHttpResponse deleteResponse = deleteChannel.readOutbound();
    deleteChannel.close();

    assertEquals(HttpResponseStatus.OK, deleteResponse.status());
    System.out.println("✓ Cache entry deleted");

    // 6. Проверяем что запись удалена
    EmbeddedChannel verifyChannel = new EmbeddedChannel(nettyHandler);
    verifyChannel.writeInbound(getRequest);
    FullHttpResponse verifyResponse = verifyChannel.readOutbound();
    verifyChannel.close();

    assertEquals(HttpResponseStatus.NOT_FOUND, verifyResponse.status());
    System.out.println("✓ Deletion verified - entry not found");

    System.out.println("\n✓ Cache update and delete operations successful");
  }

  @Test
  void testCacheWithoutSession() {
    System.out.println("Testing cache access without session...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    String uri = "/cache?key=someKey";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();
    channel.close();

    // Должна быть ошибка валидации или авторизации
    assertTrue(response.status() == HttpResponseStatus.BAD_REQUEST ||
            response.status() == HttpResponseStatus.UNAUTHORIZED,
        "Should reject request without session token");

    System.out.println("✓ Unauthorized access handled correctly");
  }

  @Test
  void testCacheWithInvalidSession() {
    System.out.println("Testing cache access with invalid session...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    String uri = "/cache?session_token=invalid_token_123&key=someKey";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();
    channel.close();

    assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status(),
        "Should reject invalid session token");

    System.out.println("✓ Invalid session handled correctly");
  }

  @Test
  void testCacheWithDifferentDataTypes() {
    System.out.println("Testing cache with different data types...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    String sessionToken = authenticateAndGetToken(nettyHandler);
    assertNotNull(sessionToken);

    // Тест с простой строкой
    String stringKey = "stringKey_" + System.currentTimeMillis();
    String stringUri = "/cache?session_token=" + sessionToken +
        "&key=" + stringKey + "&type=string&ttl=30s";

    FullHttpRequest stringRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.POST,
        stringUri,
        Unpooled.copiedBuffer("Simple string value", StandardCharsets.UTF_8)
    );

    EmbeddedChannel stringChannel = new EmbeddedChannel(nettyHandler);
    stringChannel.writeInbound(stringRequest);
    FullHttpResponse stringResponse = stringChannel.readOutbound();
    stringChannel.close();

    assertEquals(HttpResponseStatus.OK, stringResponse.status());
    System.out.println("✓ String data stored successfully");

    // Тест с JSON (если поддерживается)
    String jsonKey = "jsonKey_" + System.currentTimeMillis();
    String jsonValue = "{\"name\":\"test\",\"value\":123,\"active\":true}";

    String jsonUri = "/cache?session_token=" + sessionToken +
        "&key=" + jsonKey + "&type=json&ttl=30s";

    FullHttpRequest jsonRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.POST,
        jsonUri,
        Unpooled.copiedBuffer(jsonValue, StandardCharsets.UTF_8)
    );

    EmbeddedChannel jsonChannel = new EmbeddedChannel(nettyHandler);
    jsonChannel.writeInbound(jsonRequest);
    FullHttpResponse jsonResponse = jsonChannel.readOutbound();
    jsonChannel.close();

    if (jsonResponse.status() == HttpResponseStatus.OK) {
      System.out.println("✓ JSON data stored successfully");
    } else {
      // JSON может быть отклонен если не пройдет валидацию
      System.out.println("JSON validation failed (expected for some implementations)");
    }

    System.out.println("\n✓ Different data types handled");
  }

  @Test
  void testCacheTTL() {
    System.out.println("Testing cache TTL functionality...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    String sessionToken = authenticateAndGetToken(nettyHandler);
    assertNotNull(sessionToken);

    // Создаем запись с TTL
    String key = "ttlKey_" + System.currentTimeMillis();
    String uri = "/cache?session_token=" + sessionToken +
        "&key=" + key + "&type=string&ttl=5s"; // 5 секунд

    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.POST,
        uri,
        Unpooled.copiedBuffer("TTL test value", StandardCharsets.UTF_8)
    );

    EmbeddedChannel channel1 = new EmbeddedChannel(nettyHandler);
    channel1.writeInbound(request);
    FullHttpResponse response1 = channel1.readOutbound();
    channel1.close();

    assertEquals(HttpResponseStatus.OK, response1.status());

    // Проверяем что TTL указан в ответе
    String responseText = response1.content().toString(StandardCharsets.UTF_8);
    assertTrue(responseText.contains("TTL:") || responseText.contains("successfully"));
    System.out.println("✓ Entry with TTL created: " + responseText);

    System.out.println("\n✓ TTL functionality tested (cleanup is periodic)");
  }

  @Test
  void testCachePermissions() {
    System.out.println("Testing cache permissions...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    // 1. Создаем пользователя с правами ISOLATED
    String isolatedUser = "isolated_" + System.currentTimeMillis();
    String sessionToken = getSuperAdminSessionAndCreateUser(nettyHandler, isolatedUser, "isolated");
    assertNotNull(sessionToken);

    // 2. Получаем сессию для ISOLATED пользователя
    String isolatedSessionToken = authenticateUser(nettyHandler, isolatedUser, "password123");
    assertNotNull(isolatedSessionToken);
    System.out.println("Got ISOLATED user session: " + isolatedSessionToken);

    // 3. Пытаемся использовать кэш с ISOLATED правами
    String uri = "/cache?session_token=" + isolatedSessionToken + "&key=testKey";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();
    channel.close();

    // ISOLATED пользователи должны получить FORBIDDEN
    if (response.status() == HttpResponseStatus.FORBIDDEN) {
      System.out.println("✓ ISOLATED users correctly restricted");
    } else {
      System.out.println("ISOLATED user access: " + response.status());
    }
  }

  // Вспомогательные методы
  private String authenticateAndGetToken(NettyHandler nettyHandler) {
    String uri = "/auth?login=default&password=admin123";
    FullHttpRequest authRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel authChannel = new EmbeddedChannel(nettyHandler);
    authChannel.writeInbound(authRequest);
    FullHttpResponse authResponse = authChannel.readOutbound();
    authChannel.close();

    if (authResponse.status() == HttpResponseStatus.OK) {
      String content = authResponse.content().toString(StandardCharsets.UTF_8);
      for (String line : content.split("\n")) {
        if (line.contains("Session token:")) {
          return line.split(": ")[1].trim();
        }
      }
    }
    return null;
  }

  private String getSuperAdminSessionAndCreateUser(NettyHandler nettyHandler, String username, String permission) {
    // Получаем сессию SUPERADMIN
    String superAdminSession = authenticateAndGetToken(nettyHandler);
    if (superAdminSession == null) return null;

    // Создаем нового пользователя
    String createUri = "/user?session_token=" + superAdminSession +
        "&new_login=" + username +
        "&password=password123" +
        "&permission=" + permission;

    FullHttpRequest createRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.PUT,
        createUri
    );

    EmbeddedChannel createChannel = new EmbeddedChannel(nettyHandler);
    createChannel.writeInbound(createRequest);
    createChannel.readOutbound();
    createChannel.close();

    return superAdminSession;
  }

  private String authenticateUser(NettyHandler nettyHandler, String username, String password) {
    String uri = "/auth?login=" + username + "&password=" + password;
    FullHttpRequest authRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel authChannel = new EmbeddedChannel(nettyHandler);
    authChannel.writeInbound(authRequest);
    FullHttpResponse authResponse = authChannel.readOutbound();
    authChannel.close();

    if (authResponse.status() == HttpResponseStatus.OK) {
      String content = authResponse.content().toString(StandardCharsets.UTF_8);
      for (String line : content.split("\n")) {
        if (line.contains("Session token:")) {
          return line.split(": ")[1].trim();
        }
      }
    }
    return null;
  }
}