package EndToEndTest;

import com.mipt.Main;
import com.mipt.database.initialization.DatabaseConnection;
import java.sql.Connection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ServerSetup {
  protected static volatile Thread serverThread;
  protected static volatile boolean serverStarted = false;

  @BeforeAll
  void startServer() throws Exception {
    if (!serverStarted) {
      synchronized (ServerSetup.class) {
        if (!serverStarted) {
          System.out.println("Starting server...");

          // Проверяем что БД доступна
          assertTrue(checkDatabase(), "Database should be available before starting server");

          // Запускаем сервер в отдельном потоке
          serverThread = new Thread(() -> {
            try {
              Main.main(new String[]{});
            } catch (Exception e) {
              if (!isExpectedShutdownException(e)) {
                e.printStackTrace();
              }
            }
          });
          serverThread.setDaemon(true);
          serverThread.start();

          // Даем серверу время на запуск
          waitForServerStartup();

          // Проверяем что сервер запустился
          assertTrue(isServerReady(), "Server should be ready on port 8080");

          serverStarted = true;
          System.out.println("Server started successfully");
        }
      }
    }
  }

  private boolean checkDatabase() {
      try (Connection conn = DatabaseConnection.getConnection()) {
        return conn != null && !conn.isClosed();
      } catch (Exception e) {
        System.err.println("Database connection failed: " + e.getMessage());
        return false;
    }
  }

  private void waitForServerStartup() {
    try {
      Thread.sleep(3000); // Даем серверу время на запуск
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean isServerReady() {
    try {
      // Простая проверка что порт слушается
      java.net.Socket socket = new java.net.Socket("localhost", 8080);
      socket.close();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isExpectedShutdownException(Exception e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof InterruptedException) {
        return true;
      }
      if (cause.getMessage() != null && cause.getMessage().contains("Interrupted")) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}