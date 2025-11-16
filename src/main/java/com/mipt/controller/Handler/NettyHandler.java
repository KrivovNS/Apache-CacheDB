package com.mipt.controller.Handler;

import com.mipt.cache.CacheStorage;
import com.mipt.userstorage.dao.UserDAO;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

public class NettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  private final CacheRequestHandler cacheHandler;
  private final StorageRequestHandler storageHandler;
  private final InfoPageHandler infoHandler;

  public NettyHandler(CacheStorage cacheStorage, UserDAO userDAO) {
    this.cacheHandler = new CacheRequestHandler(cacheStorage, userDAO);
    this.storageHandler = new StorageRequestHandler(cacheStorage, userDAO);
    this.infoHandler = new InfoPageHandler();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
    String method = request.method().name();
    String uri = request.uri();

    System.out.println("Received " + method + " request: " + uri);

    if ("/".equals(uri)) {
      infoHandler.sendInfoPage(ctx);
      return;
    }

    String content = request.content().toString(CharsetUtil.UTF_8);
    FullHttpResponse response;

    if (uri.startsWith("/cache")) {
      response = cacheHandler.handleRequest(method, uri, content, ctx);
    } else if (uri.startsWith("/storage")) {
      response = storageHandler.handleRequest(method, uri, content);
    } else {
      response = createErrorResponse(HttpResponseStatus.NOT_FOUND, "Endpoint not found: " + uri);
    }

    ctx.writeAndFlush(response);
  }

  private FullHttpResponse createErrorResponse(HttpResponseStatus status, String message) {
    return cacheHandler.createResponse(status, message);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    System.err.println("Error processing request: " + cause.getMessage());
    cause.printStackTrace();

    FullHttpResponse errorResponse = createErrorResponse(
        HttpResponseStatus.INTERNAL_SERVER_ERROR,
        "Server error: " + cause.getMessage()
    );
    ctx.writeAndFlush(errorResponse);
  }
}