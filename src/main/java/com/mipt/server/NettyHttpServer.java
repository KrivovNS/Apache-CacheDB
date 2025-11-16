package com.mipt.server;

import com.mipt.controller.Handler.NettyHandler;
import com.mipt.service.CacheStorageService;
import com.mipt.userstorage.dao.UserDAO;
import com.mipt.userstorage.dao.PermissionDAO;
import com.mipt.userstorage.dao.CacheStorageDAO;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class NettyHttpServer {

  private final int port;
  private final CacheStorageService cacheService;
  private final UserDAO userDAO;
  private final PermissionDAO permissionDAO;
  private final CacheStorageDAO cacheStorageDAO;

  public NettyHttpServer(int port, CacheStorageService cacheService, UserDAO userDAO,
      PermissionDAO permissionDAO, CacheStorageDAO cacheStorageDAO) {
    this.port = port;
    this.cacheService = cacheService;
    this.userDAO = userDAO;
    this.permissionDAO = permissionDAO;
    this.cacheStorageDAO = cacheStorageDAO;
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
            protected void initChannel(SocketChannel ch) throws Exception {
              ch.pipeline().addLast(
                  new HttpServerCodec(),
                  new HttpObjectAggregator(1048576),
                  new NettyHandler(cacheService, userDAO, permissionDAO, cacheStorageDAO)
              );
            }
          })
          .option(ChannelOption.SO_BACKLOG, 128)
          .childOption(ChannelOption.SO_KEEPALIVE, true);

      Channel ch = b.bind(port).sync().channel();
      System.out.println("HTTP Cache Server started on port " + port);

      ch.closeFuture().sync();
    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}