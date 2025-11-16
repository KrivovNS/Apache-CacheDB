package com.mipt.server;

import com.mipt.cache.CacheStorage;
import com.mipt.controller.NettyHandler;
import com.mipt.userstorage.dao.UserDAO;
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
  private final CacheStorage cacheStorage;
  private final UserDAO userDAO;

  public NettyHttpServer(int port, CacheStorage cacheStorage, UserDAO userDAO) {
    this.port = port;
    this.cacheStorage = cacheStorage;
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
            protected void initChannel(SocketChannel ch) throws Exception {
              ch.pipeline().addLast(
                  new HttpServerCodec(),
                  new HttpObjectAggregator(1048576),
                  new NettyHandler(cacheStorage, userDAO)
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

  public static void main(String[] args) throws Exception {
    int port = 8080;
    if (args.length > 0) {
      port = Integer.parseInt(args[0]);
    }

    // Создаем экземпляры необходимых компонентов
    CacheStorage cacheStorage = new CacheStorage(1000); // capacity = 1000
    UserDAO userDAO = new UserDAO(); // Предполагается, что UserDAO имеет конструктор по умолчанию

    new NettyHttpServer(port, cacheStorage, userDAO).run();
  }
}