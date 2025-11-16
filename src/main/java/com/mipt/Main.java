package com.mipt;

import com.mipt.server.NettyHttpServer;
import com.mipt.service.CacheStorageService;
import com.mipt.userstorage.dao.UserDAO;
import com.mipt.userstorage.dao.PermissionDAO;
import com.mipt.userstorage.dao.CacheStorageDAO;

public class Main {

  public static void main(String[] args) {
    try {
      int port = 8080;
      if (args.length > 0) {
        port = Integer.parseInt(args[0]);
      }

      // Создаем экземпляры DAO
      UserDAO userDAO = new UserDAO();
      PermissionDAO permissionDAO = new PermissionDAO();
      CacheStorageDAO cacheStorageDAO = new CacheStorageDAO();

      // Создаем сервис кэширования
      CacheStorageService cacheService = new CacheStorageService(cacheStorageDAO);

      System.out.println("Starting HTTP Cache Server on port " + port + "...");

      NettyHttpServer server = new NettyHttpServer(port, cacheService, userDAO, permissionDAO,
          cacheStorageDAO);
      server.run();

    } catch (Exception e) {
      System.err.println("Server error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }
}