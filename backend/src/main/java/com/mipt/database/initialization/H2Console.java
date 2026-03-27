package com.mipt.database.initialization;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.Properties;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class H2Console {

  private static final Logger log = LoggerFactory.getLogger(H2Console.class);

  public static void main(String[] args) throws SQLException {
    int h2Port = 8082;
    try {
      Properties props = new Properties();
      try (InputStream input = H2Console.class.getClassLoader().getResourceAsStream("application.properties")) {
        if (input != null) {
          props.load(input);
        }
      }
      h2Port = Integer.parseInt(props.getProperty("h2.console.port", "8082"));
    } catch (Exception e) {
      log.warn("Could not read H2 console port from config, using default 8082");
    }

    Server server = Server.createWebServer("-web", "-webAllowOthers", "-webPort", String.valueOf(h2Port)).start();
    log.info("H2 Console started at: http://localhost:{}", h2Port);
    log.info("Press Enter to stop...");

    try {
      System.in.read();
    } catch (Exception e) {
      log.error("Error reading input", e);
    }

    server.stop();
  }
}
