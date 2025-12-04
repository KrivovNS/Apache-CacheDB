package EndToEndTest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AuthenticationTest extends BaseTestClass {

  @Test
  void testUserRegistrationAndSessionCreation() throws Exception {
    String testUser = "testuser_" + System.currentTimeMillis();

    // Создание хранилища и получение session_token
    String sessionToken = createStorageAndGetSession(testUser, "testpass");
    assertNotNull(sessionToken, "Session token should not be null");
    assertFalse(sessionToken.isEmpty(), "Session token should not be empty");

    // Проверяем что токен имеет разумную длину (не слишком короткий)
    assertTrue(sessionToken.length() >= 8,
        "Session token should have reasonable length, got: " + sessionToken.length());

    // DEBUG: Выводим токен для отладки
    System.out.println("Generated session token: " + sessionToken);
    System.out.println("Token length: " + sessionToken.length());
  }

  @Test
  void testUserAuthentication() throws Exception {
    String testUser = "auth_test_user_" + System.currentTimeMillis();

    // Тестируем создание нового пользователя
    String sessionToken1 = createStorageAndGetSession(testUser, "password123");
    assertNotNull(sessionToken1);
    assertFalse(sessionToken1.isEmpty());

    // Тестируем логин существующего пользователя (должен вернуть тот же или новый токен)
    String sessionToken2 = createStorageAndGetSession(testUser, "password123");
    assertNotNull(sessionToken2);
    assertFalse(sessionToken2.isEmpty());

    // Тестируем неверный пароль
    String uri = "/storage?login=" + java.net.URLEncoder.encode(testUser, java.nio.charset.StandardCharsets.UTF_8) +
        "&password=wrongpass";
    String response = sendHttpRequest("POST", uri, null);
    assertTrue(response.contains("Invalid password") ||
            response.contains("Unauthorized") ||
            response.contains("Authentication failed"),
        "Should reject wrong password. Response: " + response);
  }
}