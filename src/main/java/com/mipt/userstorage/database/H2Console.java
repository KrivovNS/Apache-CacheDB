package com.mipt.userstorage.database;

import org.h2.tools.Server;
import java.sql.SQLException;

public class H2Console {
  public static void main(String[] args) throws SQLException {
    // Запускаем веб-консоль H2 на порту 8082
    Server server = Server.createWebServer("-web", "-webAllowOthers", "-webPort", "8082").start();
    System.out.println("H2 Console started at: http://localhost:8082");
    System.out.println("Press Enter to stop...");
    try {
      System.in.read();
    } catch (Exception e) {
      e.printStackTrace();
    }
    server.stop();
  }
}