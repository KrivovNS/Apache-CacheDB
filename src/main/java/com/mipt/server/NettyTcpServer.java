package com.mipt.server;

import com.mipt.controller.NettyTcpHandler;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import com.mipt.database.dao.UserDAO;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyTcpServer {

  private static final Logger log = LoggerFactory.getLogger(NettyTcpServer.class);

  private final int port;
  private final CacheStorageService cacheService;
  private final SessionService sessionService;
  private final UserDAO userDAO;

  public NettyTcpServer(int port,
      CacheStorageService cacheService,
      SessionService sessionService,
      UserDAO userDAO) {
    this.port = port;
    this.cacheService = cacheService;
    this.sessionService = sessionService;
    this.userDAO = userDAO;
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

              p.addLast(new LineBasedFrameDecoder(8192));
              p.addLast(new StringDecoder(CharsetUtil.UTF_8));
              p.addLast(new StringEncoder(CharsetUtil.UTF_8));
              p.addLast(new NettyTcpHandler(cacheService, sessionService, userDAO));
            }
          })
          .option(ChannelOption.SO_BACKLOG, 128)
          .childOption(ChannelOption.SO_KEEPALIVE, true);

      ChannelFuture f = b.bind(port).sync();
      log.info("Netty TCP Server started on port {}", port);

      f.channel().closeFuture().sync();
    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}
