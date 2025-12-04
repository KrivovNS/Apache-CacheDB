package EndToEndTest;

import com.mipt.database.dao.UserDAO;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.model.User;
import com.mipt.Main;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SystemTest extends BaseTestClass {

  private static volatile Thread serverThread;

  @BeforeAll
  static void setUp() {
    // Проверяем что БД доступна перед запуском сервера
    assertTrue(isDatabaseAvailable(), "Database should be available before starting server");

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
    assertTrue(isServerReady(), "Server should be ready on port " + PORT);

    // Проверяем что БД работает после запуска сервера
    assertTrue(isDatabaseFunctional(), "Database should be functional after server startup");
  }
  @AfterAll
  static void tearDown() {
    if (group != null) {
      group.shutdownGracefully();
    }
  }

  @Test
  @Order(1)
  void testDatabaseInitialization() throws Exception {
    // Проверяем что БД была корректно инициализирована
    assertTrue(isDatabaseAvailable(), "Database should be available");
    assertTrue(isDatabaseFunctional(), "Database should be functional");

    // Проверяем что таблица users существует и имеет правильную структуру
    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) as table_count FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_NAME = 'USERS' AND TABLE_SCHEMA = 'PUBLIC'")) {

      assertTrue(rs.next(), "Should have result for table check");
      int tableCount = rs.getInt("table_count");
      assertEquals(1, tableCount, "Users table should exist");
    }
  }

  @Test
  @Order(2)
  void testInfoEndpoint() throws Exception {
    String response = sendHttpRequest("GET", "/", null);
    assertTrue(response.contains("Cache Storage HTTP Server"));
    assertTrue(response.contains("Available endpoints"));
    assertTrue(response.contains("CACHE OPERATIONS"));
    assertTrue(response.contains("STORAGE OPERATIONS"));
  }

  @Test
  @Order(3)
  void testServerAndDatabaseIntegration() throws Exception {
    cleanIntegrationTestData();

    // Создаем пользователя через HTTP API
    String sessionToken = createStorageAndGetSession("integration_test_user", "integration_pass");
    assertNotNull(sessionToken, "Should create user and get session token");

    // Проверяем что пользователь действительно создан в БД
    UserDAO userDAO = new UserDAO();
    User createdUser = userDAO.findByUsername("integration_test_user");
    assertNotNull(createdUser, "User should be persisted in database");
    assertEquals("integration_test_user", createdUser.getUsername());

    // Используем сессию для операций с кэшем
    String key = "integration_key";
    String value = "integration_value";

    String insertUri = buildCacheUri(sessionToken, key, "string");
    String insertResponse = sendHttpRequest("POST", insertUri, value);
    assertTrue(insertResponse.contains("successfully") || insertResponse.contains("Success"),
        "Should insert data using created session");

    // Проверяем что данные можно прочитать
    String readResponse = sendHttpRequest("GET", insertUri, null);
    assertEquals(value, readResponse, "Should read back the stored value");

    // Очищаем тестовые данные
    cleanIntegrationTestData();
  }

  // Системные вспомогательные методы
  private static boolean isDatabaseAvailable() {
    try (Connection conn = DatabaseConnection.getConnection()) {
      return conn != null && !conn.isClosed();
    } catch (Exception e) {
      System.err.println("Database connection failed: " + e.getMessage());
      return false;
    }
  }

  private static boolean isDatabaseFunctional() {
    try {
      UserDAO userDAO = new UserDAO();
      User testUser = userDAO.createUser("test_db_user", "testpass", "test_storage_token");
      if (testUser == null) {
        return false;
      }

      User foundUser = userDAO.findByUsername("test_db_user");
      if (foundUser == null) {
        return false;
      }

      boolean dataCorrect = foundUser.getUsername().equals("test_db_user") &&
          foundUser.getStorageToken().equals("test_storage_token");

      cleanTestData();

      return dataCorrect;

    } catch (Exception e) {
      System.err.println("Database functional test failed: " + e.getMessage());
      return false;
    }
  }

  private static void cleanTestData() {
    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM users WHERE username = 'test_db_user'");
    } catch (Exception e) {
      System.err.println("Failed to clean test data: " + e.getMessage());
    }
  }

  private static boolean isServerReady() {
    int attempts = 0;
    int maxAttempts = 15;

    while (attempts < maxAttempts) {
      try {
        String response = sendHttpRequest("GET", "/", null);
        if (response.contains("Cache Storage HTTP Server")) {
          System.out.println("Server is ready after " + ((attempts + 1) * 500) + "ms");
          return true;
        }
      } catch (Exception e) {
        // Ожидаемо - сервер еще не готов
        System.out.println(
            "Server not ready yet, attempt " + (attempts + 1) + ": " + e.getMessage());
      }

      attempts++;
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    System.err.println("Server failed to start within " + (maxAttempts * 500) + "ms");
    return false;
  }

  private static void waitForServerStartup() {
    try {
      // Постепенно увеличиваем время ожидания
      for (int i = 0; i < 3; i++) {
        Thread.sleep(1000); // Ждем 1 секунду
        if (isServerReady()) {
          return;
        }
      }
      // Если сервер не запустился за 3 секунды, ждем еще 2
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isExpectedShutdownException(Exception e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof InterruptedException) {
        return true;
      }
      if (cause.getMessage() != null && (
          cause.getMessage().contains("Interrupted"))) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private static void cleanIntegrationTestData() {
    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM cache_storage WHERE storage_token IN " +
          "(SELECT storage_token FROM users WHERE username = 'integration_test_user')");
      stmt.execute("DELETE FROM users WHERE username = 'integration_test_user'");
    } catch (Exception e) {
      System.err.println("Failed to clean integration test data: " + e.getMessage());
    }
  }
}