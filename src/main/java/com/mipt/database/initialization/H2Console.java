package com.mipt.database.initialization;

import org.h2.tools.Server;
import java.sql.SQLException;
import com.mipt.util.AppLogger;


public class H2Console {

  private static final AppLogger log = AppLogger.getLogger(H2Console.class);

  public static void main(String[] args) throws SQLException {
    // Запускаем веб-консоль H2 на порту 8082
    Server server = Server.createWebServer("-web", "-webAllowOthers", "-webPort", "8082").start();
    log.info("H2 Console started at: http://localhost:8082");
    log.info("Press Enter to stop...");
    try {
      System.in.read();
    } catch (Exception e) {
      log.error("Error reading input", e);
    }
    server.stop();
  }
}