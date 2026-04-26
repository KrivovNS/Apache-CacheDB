package com.mipt.server;

import com.mipt.controller.NettyHandler;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.telemetry.TelemetryService;
import com.mipt.database.dao.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyHttpServer {

  private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

  private final int port;
  private final CacheStorageService cacheService;
  private final SessionService sessionService;
  private final UserDAO userDAO;
  private final TelemetryService telemetryService;

  public NettyHttpServer(int port,
      CacheStorageService cacheService,
      SessionService sessionService,
      UserDAO userDAO,
      TelemetryService telemetryService) {
    this.port = port;
    this.cacheService = cacheService;
    this.sessionService = sessionService;
    this.userDAO = userDAO;
    this.telemetryService = telemetryService;
  }

  public NettyHttpServer(int port,
      CacheStorageService cacheService,
      SessionService sessionService,
      UserDAO userDAO) {
    this(port, cacheService, sessionService, userDAO, TelemetryService.disabled(cacheService));
  }

  public void run() throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup();

    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .handler(new LoggingHandler(LogLevel.INFO))
          .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
              ChannelPipeline p = ch.pipeline();

              p.addLast(new HttpServerCodec());
              p.addLast(new HttpObjectAggregator(65536));
              p.addLast(new NettyHandler(cacheService, sessionService, userDAO, telemetryService));
            }
          })
          .option(ChannelOption.SO_BACKLOG, 128)
          .childOption(ChannelOption.SO_KEEPALIVE, true);

      ChannelFuture f = b.bind(port).sync();
      log.info("Netty HTTP Server started on port {}", port);


      f.channel().closeFuture().sync();

    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}
