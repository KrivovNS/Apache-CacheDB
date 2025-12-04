package EndToEndTest;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public abstract class BaseTestClass extends ServerSetup {
  protected static final String HOST = "localhost";
  protected static final int PORT = 8080;
  protected static EventLoopGroup group;

  @BeforeAll
  @Override
  void startServer() throws Exception {
    super.startServer(); // Запускаем сервер через родительский класс
    group = new NioEventLoopGroup(); // Инициализируем EventLoopGroup
  }

  @AfterAll
  static void cleanupGroup() {
    if (group != null) {
      group.shutdownGracefully();
    }
  }

  protected static String sendHttpRequest(String method, String uri, String body) throws Exception {
    // Проверяем что сервер запущен
    if (!serverStarted) {
      throw new IllegalStateException("Server is not started");
    }

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

  protected String buildCacheUri(String sessionToken, String key, String type) {
    return "/cache?session_token=" + sessionToken +
        "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) +
        "&type=" + type;
  }

  protected String createStorageAndGetSession(String login, String password) throws Exception {
    String uri = "/storage?login=" + URLEncoder.encode(login, StandardCharsets.UTF_8) +
        "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

    String response = sendHttpRequest("POST", uri, null);

    // Разные возможные форматы ответа
    if (response.contains("Session token:")) {
      return response.substring(response.indexOf("Session token:") + 14).trim();
    } else if (response.contains("token:")) {
      return response.substring(response.indexOf("token:") + 6).trim();
    } else if (response.contains(":")) {
      // Пробуем взять все после первого двоеточия
      return response.substring(response.indexOf(":") + 1).trim();
    } else {
      // Если не нашли двоеточие, возвращаем весь ответ
      return response.trim();
    }
  }
}