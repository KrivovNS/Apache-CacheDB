import com.mipt.controller.DataType;
import com.mipt.database.dao.UserDAO;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.model.User;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mipt.Main;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class CacheStorageEndToEndTest {

  private static final String HOST = "localhost";
  private static final int PORT = 8080;
  private static EventLoopGroup group;
  private static volatile Thread serverThread;

  @BeforeAll
  static void setUp() {
    group = new NioEventLoopGroup();

    // Проверяем что БД доступна перед запуском сервера
    assertTrue(isDatabaseAvailable(), "Database should be available before starting server");

    // Запускаем сервер в отдельном потоке
    serverThread = new Thread(() -> {
      try {
        Main.main(new String[]{});
      } catch (Exception e) {
        if (!isExpectedShutdownException(e)) {
          e.printStackTrace();
        }
      }
    });
    serverThread.setDaemon(true);
    serverThread.start();

    // Даем серверу время на запуск
    waitForServerStartup();

    // Проверяем что сервер запустился
    assertTrue(isServerReady(), "Server should be ready on port " + PORT);

    // Проверяем что БД работает после запуска сервера
    assertTrue(isDatabaseFunctional(), "Database should be functional after server startup");
  }

  @AfterAll
  static void tearDown() {
    if (group != null) {
      group.shutdownGracefully();
    }
  }

  /**
   * Проверяет что БД доступна и можно установить соединение
   */
  private static boolean isDatabaseAvailable() {
    try (Connection conn = DatabaseConnection.getConnection()) {
      return conn != null && !conn.isClosed();
    } catch (Exception e) {
      System.err.println("Database connection failed: " + e.getMessage());
      return false;
    }
  }

  /**
   * Проверяет что БД функционирует корректно после запуска сервера
   */
  private static boolean isDatabaseFunctional() {
    try {
      // Проверяем что таблица users существует и доступна
      UserDAO userDAO = new UserDAO();

      // Пробуем создать тестового пользователя
      User testUser = userDAO.createUser("test_db_user", "testpass", "test_storage_token");
      if (testUser == null) {
        return false;
      }

      // Пробуем найти созданного пользователя
      User foundUser = userDAO.findByUsername("test_db_user");
      if (foundUser == null) {
        return false;
      }

      // Проверяем что данные сохранились корректно
      boolean dataCorrect = foundUser.getUsername().equals("test_db_user") &&
          foundUser.getStorageToken().equals("test_storage_token");

      // Очищаем тестовые данные
      cleanTestData();

      return dataCorrect;

    } catch (Exception e) {
      System.err.println("Database functional test failed: " + e.getMessage());
      return false;
    }
  }

  /**
   * Очищает тестовые данные из БД
   */
  private static void cleanTestData() {
    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement()) {

      stmt.execute("DELETE FROM users WHERE username = 'test_db_user'");

    } catch (Exception e) {
      System.err.println("Failed to clean test data: " + e.getMessage());
    }
  }

  /**
   * Проверяет что сервер готов принимать запросы
   */
  private static boolean isServerReady() {
    int attempts = 0;
    int maxAttempts = 15;

    while (attempts < maxAttempts) {
      try {
        String response = sendHttpRequest("GET", "/", null);
        if (response.contains("Cache Storage HTTP Server")) {
          System.out.println("Server is ready after " + ((attempts + 1) * 500) + "ms");
          return true;
        }
      } catch (Exception e) {
        // Ожидаемо - сервер еще не готов
        System.out.println("Server not ready yet, attempt " + (attempts + 1) + ": " + e.getMessage());
      }

      attempts++;
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    System.err.println("Server failed to start within " + (maxAttempts * 500) + "ms");
    return false;
  }

  /**
   * Ожидает запуск сервера
   */
  private static void waitForServerStartup() {
    try {
      // Постепенно увеличиваем время ожидания
      for (int i = 0; i < 3; i++) {
        Thread.sleep(1000); // Ждем 1 секунду
        if (isServerReady()) {
          return;
        }
      }
      // Если сервер не запустился за 3 секунды, ждем еще 2
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Проверяет является ли исключение ожидаемым при завершении
   */
  private static boolean isExpectedShutdownException(Exception e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof InterruptedException) {
        return true;
      }
      if (cause.getMessage() != null && (
          cause.getMessage().contains("Interrupted"))) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  @Test
  void testDatabaseInitialization() throws Exception {
    // Проверяем что БД была корректно инициализирована
    assertTrue(isDatabaseAvailable(), "Database should be available");
    assertTrue(isDatabaseFunctional(), "Database should be functional");

    // Проверяем что таблица users существует и имеет правильную структуру
    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) as table_count FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_NAME = 'USERS' AND TABLE_SCHEMA = 'PUBLIC'")) {

      assertTrue(rs.next(), "Should have result for table check");
      int tableCount = rs.getInt("table_count");
      assertEquals(1, tableCount, "Users table should exist");
    }
  }

  @Test
  void testServerAndDatabaseIntegration() throws Exception {
    // Создаем пользователя через HTTP API
    String sessionToken = createStorageAndGetSession("integration_test_user", "integration_pass");
    assertNotNull(sessionToken, "Should create user and get session token");

    // Проверяем что пользователь действительно создан в БД
    UserDAO userDAO = new UserDAO();
    User createdUser = userDAO.findByUsername("integration_test_user");
    assertNotNull(createdUser, "User should be persisted in database");
    assertEquals("integration_test_user", createdUser.getUsername());

    // Используем сессию для операций с кэшем
    String key = "integration_key";
    String value = "integration_value";

    String insertUri = buildCacheUri(sessionToken, key, DataType.STRING.getValue());
    String insertResponse = sendHttpRequest("POST", insertUri, value);
    assertTrue(insertResponse.contains("successfully") || insertResponse.contains("Success"),
        "Should insert data using created session");

    // Проверяем что данные можно прочитать
    String readResponse = sendHttpRequest("GET", insertUri, null);
    assertEquals(value, readResponse, "Should read back the stored value");

    // Очищаем тестовые данные
    cleanIntegrationTestData();
  }

  /**
   * Очищает данные интеграционного теста
   */
  private static void cleanIntegrationTestData() {
    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement()) {

      stmt.execute("DELETE FROM users WHERE username = 'integration_test_user'");

    } catch (Exception e) {
      System.err.println("Failed to clean integration test data: " + e.getMessage());
    }
  }

  @Test
  void testCompleteWorkflow() throws Exception {
    // 1. Создание хранилища и получение session_token
    String sessionToken = createStorageAndGetSession("testuser", "testpass");
    assertNotNull(sessionToken, "Session token should not be null");
    assertFalse(sessionToken.isEmpty(), "Session token should not be empty");

    // 2. Тестирование операций с разными типами данных
    testStringOperations(sessionToken);
    testJsonOperations(sessionToken);
    testBytesOperations(sessionToken);

    // 3. Тестирование ошибок
    testErrorCases(sessionToken);
  }

  private String createStorageAndGetSession(String login, String password) throws Exception {
    String uri = "/storage?login=" + URLEncoder.encode(login, StandardCharsets.UTF_8) +
        "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

    String response = sendHttpRequest("POST", uri, null);
    assertTrue(
        response.contains("Session token:") || response.contains("Storage created successfully"),
        "Response should contain session token");

    // Извлекаем session token из ответа
    return response.substring(response.indexOf(": ") + 2).trim();
  }

  private void testStringOperations(String sessionToken) throws Exception {
    String key = "string_key" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String value = "Hello, World!";

    // INSERT
    String insertUri = buildCacheUri(sessionToken, key, DataType.STRING.getValue());
    String insertResponse = sendHttpRequest("POST", insertUri, value);
    assertTrue(insertResponse.contains("successfully") || insertResponse.contains("Success"),
        "Insert should succeed: " + insertResponse);

    // READ
    String readUri = buildCacheUri(sessionToken, key, DataType.STRING.getValue());
    String readResponse = sendHttpRequest("GET", readUri, null);
    assertEquals(value, readResponse, "Read should return the original value");

    // UPDATE
    String updatedValue = "Updated string value";
    String updateResponse = sendHttpRequest("PUT", insertUri, updatedValue);
    assertTrue(updateResponse.contains("successfully") || updateResponse.contains("Success"),
        "Update should succeed: " + updateResponse);

    // READ after UPDATE
    String readAfterUpdate = sendHttpRequest("GET", readUri, null);
    assertEquals(updatedValue, readAfterUpdate, "Read after update should return new value");

    // DELETE
    String deleteResponse = sendHttpRequest("DELETE", readUri, null);
    assertTrue(deleteResponse.contains("successfully") || deleteResponse.contains("Success"),
        "Delete should succeed: " + deleteResponse);

    // READ after DELETE
    String readAfterDelete = sendHttpRequest("GET", readUri, null);
    assertTrue(readAfterDelete.contains("not found") || readAfterDelete.contains("Not found"),
        "Read after delete should return not found: " + readAfterDelete);
  }

  private void testJsonOperations(String sessionToken) throws Exception {
    String key = "json_key_" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String validJson = "{\"name\":\"John\",\"age\":30,\"city\":\"New York\"}";
    String invalidJson = "invalid json {";

    // INSERT valid JSON
    String insertUri = buildCacheUri(sessionToken, key, DataType.JSON.getValue());
    String insertResponse = sendHttpRequest("POST", insertUri, validJson);
    assertTrue(insertResponse.contains("successfully") || insertResponse.contains("Success"),
        "Valid JSON insert should succeed: " + insertResponse);

    // READ JSON
    String readUri = buildCacheUri(sessionToken, key, DataType.JSON.getValue());
    String readResponse = sendHttpRequest("GET", readUri, null);
    assertEquals(validJson, readResponse, "Read should return the original JSON");

    // INSERT invalid JSON
    String invalidResponse = sendHttpRequest("POST", insertUri, invalidJson);
    assertTrue(invalidResponse.contains("Invalid data format") ||
            invalidResponse.contains("Bad Request") ||
            invalidResponse.contains("Invalid JSON"),
        "Invalid JSON should be rejected: " + invalidResponse);
  }

  private void testBytesOperations(String sessionToken) throws Exception {
    String key = "bytes_key" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String validBase64 = "SGVsbG8gV29ybGQ=";
    String invalidBase64 = "invalid base64!!";

    // INSERT valid Base64
    String insertUri = buildCacheUri(sessionToken, key, DataType.BYTES.getValue());
    String insertResponse = sendHttpRequest("POST", insertUri, validBase64);
    assertTrue(insertResponse.contains("successfully") || insertResponse.contains("Success"),
        "Valid Base64 insert should succeed: " + insertResponse);

    // READ Base64
    String readUri = buildCacheUri(sessionToken, key, DataType.BYTES.getValue());
    String readResponse = sendHttpRequest("GET", readUri, null);
    assertEquals(validBase64, readResponse, "Read should return the original Base64");

    // INSERT invalid Base64
    String invalidResponse = sendHttpRequest("POST", insertUri, invalidBase64);
    assertTrue(invalidResponse.contains("Invalid data format") ||
            invalidResponse.contains("Bad Request") ||
            invalidResponse.contains("Invalid Base64"),
        "Invalid Base64 should be rejected: " + invalidResponse);
  }

  private void testErrorCases(String sessionToken) throws Exception {
    // Invalid session token
    String invalidSessionUri = buildCacheUri("invalid_session_token", "test_key",
        DataType.STRING.getValue());
    String invalidSessionResponse = sendHttpRequest("GET", invalidSessionUri, null);

    boolean isRejected = invalidSessionResponse.contains("Invalid") ||
        invalidSessionResponse.contains("Unauthorized") ||
        invalidSessionResponse.contains("expired") ||
        invalidSessionResponse.contains(
            "Session token can only contain letters, numbers and underscores");

    assertTrue(isRejected,
        "Invalid session should be rejected. Response: " + invalidSessionResponse);

    // Invalid data type
    String invalidTypeUri = "/cache?session_token=" + sessionToken +
        "&key=test_key&type=invalid_type";
    String invalidTypeResponse = sendHttpRequest("GET", invalidTypeUri, null);
    assertTrue(invalidTypeResponse.contains("Invalid data type") ||
            invalidTypeResponse.contains("Bad Request"),
        "Invalid data type should be rejected: " + invalidTypeResponse);

    // Missing parameters
    String missingParamsUri = "/cache?session_token=" + sessionToken;
    String missingParamsResponse = sendHttpRequest("GET", missingParamsUri, null);
    boolean isMissingParamsRejected = missingParamsResponse.contains("Bad Request") ||
        missingParamsResponse.contains("Missing") ||
        missingParamsResponse.contains("Invalid") ||
        missingParamsResponse.contains("Parameter 'type' is required") ||
        missingParamsResponse.contains("Parameter 'key' is required") ||
        missingParamsResponse.contains("required") ||
        missingParamsResponse.contains("cannot be empty");

    assertTrue(isMissingParamsRejected,
        "Missing parameters should be rejected. Response: " + missingParamsResponse);
  }

  private String buildCacheUri(String sessionToken, String key, String type) {
    return "/cache?session_token=" + sessionToken +
        "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) +
        "&type=" + type;
  }

  private static String sendHttpRequest(String method, String uri, String body) throws Exception {
    CompletableFuture<String> future = new CompletableFuture<>();

    Bootstrap bootstrap = new Bootstrap();
    bootstrap.group(group)
        .channel(NioSocketChannel.class)
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            ChannelPipeline p = ch.pipeline();
            p.addLast(new HttpClientCodec());
            p.addLast(new HttpContentDecompressor());
            p.addLast(new SimpleChannelInboundHandler<HttpObject>() {
              private final StringBuilder responseContent = new StringBuilder();

              @Override
              protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
                if (msg instanceof HttpContent) {
                  HttpContent content = (HttpContent) msg;
                  responseContent.append(content.content().toString(StandardCharsets.UTF_8));

                  if (content instanceof LastHttpContent) {
                    future.complete(responseContent.toString());
                    ctx.close();
                  }
                }
              }

              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                future.completeExceptionally(cause);
                ctx.close();
              }
            });
          }
        });

    Channel channel = bootstrap.connect(HOST, PORT).sync().channel();

    FullHttpRequest request;
    if (body != null) {
      ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
      request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
          HttpMethod.valueOf(method), uri, content);
      request.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
    } else {
      request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
          HttpMethod.valueOf(method), uri);
    }

    request.headers().set(HttpHeaderNames.HOST, HOST);
    request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

    channel.writeAndFlush(request);

    return future.get(10, TimeUnit.SECONDS);
  }

  @Test
  void testInfoEndpoint() throws Exception {
    String response = sendHttpRequest("GET", "/", null);
    assertTrue(response.contains("Cache Storage HTTP Server"));
    assertTrue(response.contains("Available endpoints"));
    assertTrue(response.contains("CACHE OPERATIONS"));
    assertTrue(response.contains("STORAGE OPERATIONS"));
  }

  @Test
  void testUserAuthentication() throws Exception {
    // Тестируем создание нового пользователя
    String sessionToken1 = createStorageAndGetSession("newuser", "password123");
    assertNotNull(sessionToken1);

    // Тестируем логин существующего пользователя
    String sessionToken2 = createStorageAndGetSession("newuser", "password123");
    assertNotNull(sessionToken2);

    // Тестируем неверный пароль
    String uri = "/storage?login=newuser&password=wrongpass";
    String response = sendHttpRequest("POST", uri, null);
    assertTrue(response.contains("Invalid password") || response.contains("Unauthorized"));
  }
}