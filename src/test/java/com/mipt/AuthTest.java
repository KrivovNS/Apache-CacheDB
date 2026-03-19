 package com.mipt;

import com.mipt.controller.NettyHandler;
import com.mipt.database.dao.UserDAO;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.database.initialization.DatabaseInitializer;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

 @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthTest {

   @BeforeAll
   void setupTestDatabase() throws Exception {
     System.out.println("=== Setting up test database for AuthTest ===");

     try {
       Class.forName("org.h2.Driver");
     } catch (ClassNotFoundException e) {
       fail("H2 driver not found");
     }

     // Используем in-memory базу для тестов
     System.setProperty("db.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
     DatabaseInitializer.initializeDatabase();
   }

   @AfterAll
   void cleanupDatabase() {
     DatabaseConnection.closeConnection();
   }

  @Test
  void testSuccessfulAuthentication() {
    System.out.println("Testing successful authentication with password 'admin123'...");

    // Создаем новые экземпляры для каждого теста
    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    String uri = "/auth?login=default&password=admin123";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertEquals(HttpResponseStatus.OK, response.status());

    String content = response.content().toString(StandardCharsets.UTF_8);
    System.out.println("Response: " + content);

    assertTrue(content.contains("Authentication successful"));
    assertTrue(content.contains("Session token"));
    assertTrue(content.contains("User: default"));
    assertTrue(content.contains("Permission: SUPERADMIN") ||
        content.contains("Permission: superadmin"));

    System.out.println("✓ Authentication successful with password 'admin123'");
  }

  @Test
  void testInvalidPassword() {
    System.out.println("Testing invalid password...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    String uri = "/auth?login=default&password=wrongpassword";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());

    String content = response.content().toString(StandardCharsets.UTF_8);
    assertTrue(content.contains("Invalid login or password"));

    System.out.println("✓ Invalid password handled correctly");
  }

  @Test
  void testMissingParameters() {
    System.out.println("Testing missing parameters...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    // Тест без пароля
    String uri = "/auth?login=default";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());

    String content = response.content().toString(StandardCharsets.UTF_8);
    assertTrue(content.contains("Missing required parameters") ||
        content.contains("Parameter 'password' is required"));

    System.out.println("✓ Missing parameters handled correctly");
  }

  @Test
  void testNonexistentUser() {
    System.out.println("Testing nonexistent user...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler =
    new NettyHandler(cacheService, sessionService, userDAO);

    String uri = "/auth?login=nonexistentuser&password=somepass";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.GET,
        uri
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());

    String content = response.content().toString(StandardCharsets.UTF_8);
    assertTrue(content.contains("Invalid login or password"));

    System.out.println("✓ Nonexistent user handled correctly");
  }

  @Test
  void testInvalidHttpMethod() {
    System.out.println("Testing invalid HTTP method...");

    CacheStorageService cacheService = new CacheStorageService();
    SessionService sessionService = new SessionService();
    UserDAO userDAO = new UserDAO();
    NettyHandler nettyHandler = new NettyHandler(cacheService, sessionService, userDAO);

    String uri = "/auth?login=default&password=admin123";
    FullHttpRequest request = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.POST,
        uri,
        Unpooled.copiedBuffer("body", StandardCharsets.UTF_8)
    );

    EmbeddedChannel channel = new EmbeddedChannel(nettyHandler);
    channel.writeInbound(request);
    FullHttpResponse response = channel.readOutbound();

    assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());

    System.out.println("✓ Invalid HTTP method handled correctly");
  }
}