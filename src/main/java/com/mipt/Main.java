package com.mipt;

import com.mipt.userstorage.database.DatabaseInitializer;
import com.mipt.userstorage.dao.*;
import com.mipt.server.NettyHttpServer;
import com.mipt.service.CacheStorageService;
import com.mipt.cache.FileDistributor;

public class Main {
  public static void main(String[] args) {
    try {
      // 1. Инициализируем базу данных
      System.out.println("Initializing database...");
      DatabaseInitializer.initializeDatabase();

      // 2. Инициализируем DAO
      UserDAO userDAO = new UserDAO();
      CacheStorageDAO cacheStorageDAO = new CacheStorageDAO();
      PermissionDAO permissionDAO = new PermissionDAO();

      // 3. Инициализируем сервис кэшей
      CacheStorageService cacheService = new CacheStorageService(cacheStorageDAO);

      // 4. Инициализируем распределитель файлов (ДОБАВИТЬ ЭТО)
      FileDistributor fileDistributor = new FileDistributor(cacheService);

      // 5. Запускаем Netty HTTP сервер
      int port = 8080;
      NettyHttpServer server = new NettyHttpServer(
          port, cacheService, userDAO, permissionDAO, cacheStorageDAO, fileDistributor
      );

      server.run();

    } catch (Exception e) {
      System.err.println("Failed to start application: " + e.getMessage());
      e.printStackTrace();
    } finally {
      com.mipt.userstorage.database.DatabaseConnection.closeConnection();
    }
  }
}
