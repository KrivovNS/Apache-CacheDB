package EndToEndTest;

import com.mipt.controller.DataType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ErrorHandlingTest extends BaseTestClass {

  @Test
  void testErrorCases() throws Exception {
    // Создаем пользователя для теста
    String testUser = "error_test_user_" + System.currentTimeMillis();
    String sessionToken = createStorageAndGetSession(testUser, "testpass");
    assertNotNull(sessionToken, "Need valid session token for error testing");

    // Invalid session token
    String invalidSessionUri = buildCacheUri("invalid_session_token", "test_key",
        DataType.STRING.getValue());
    String invalidSessionResponse = sendHttpRequest("GET", invalidSessionUri, null);

    boolean isRejected = invalidSessionResponse.contains("Invalid") ||
        invalidSessionResponse.contains("Unauthorized") ||
        invalidSessionResponse.contains("expired") ||
        invalidSessionResponse.contains("not found") ||
        invalidSessionResponse.contains("Session token") ||
        invalidSessionResponse.contains("authentication");

    assertTrue(isRejected,
        "Invalid session should be rejected. Response: " + invalidSessionResponse);

    // Invalid data type
    String invalidTypeUri = "/cache?session_token=" + sessionToken +
        "&key=test_key&type=invalid_type";
    String invalidTypeResponse = sendHttpRequest("GET", invalidTypeUri, null);
    assertTrue(invalidTypeResponse.contains("Invalid data type") ||
            invalidTypeResponse.contains("Bad Request") ||
            invalidTypeResponse.contains("Unsupported type") ||
            invalidTypeResponse.contains("type parameter"),
        "Invalid data type should be rejected: " + invalidTypeResponse);

    // Missing parameters
    String missingParamsUri = "/cache?session_token=" + sessionToken;
    String missingParamsResponse = sendHttpRequest("GET", missingParamsUri, null);
    boolean isMissingParamsRejected = missingParamsResponse.contains("Bad Request") ||
        missingParamsResponse.contains("Missing") ||
        missingParamsResponse.contains("Invalid") ||
        missingParamsResponse.contains("Parameter") ||
        missingParamsResponse.contains("required") ||
        missingParamsResponse.contains("cannot be empty");

    assertTrue(isMissingParamsRejected,
        "Missing parameters should be rejected. Response: " + missingParamsResponse);
  }
}