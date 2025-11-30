import com.mipt.controller.DataType;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class CacheStorageEndToEndTest {

  private static final String HOST = "localhost";
  private static final int PORT = 8080;
  private static EventLoopGroup group;

  @BeforeAll
  static void setUp() {
    group = new NioEventLoopGroup();
  }

  @AfterAll
  static void tearDown() {
    if (group != null) {
      group.shutdownGracefully();
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
    ;
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

  private String sendHttpRequest(String method, String uri, String body) throws Exception {
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