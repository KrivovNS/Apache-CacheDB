package EndToEndTest;

import com.mipt.controller.DataType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DataOperationsTest extends BaseTestClass {

  @Test
  void testStringCRUDOperations() throws Exception {
    // Создаем пользователя для теста
    String testUser = "string_test_user_" + System.currentTimeMillis();
    String sessionToken = createStorageAndGetSession(testUser, "testpass");
    assertNotNull(sessionToken, "Need valid session token for string operations");
    assertFalse(sessionToken.isEmpty(), "Session token should not be empty");

    String key = "string_key_" + System.currentTimeMillis();
    String value = "Hello, World!";
    String updatedValue = "Updated string value";

    // INSERT
    String insertUri = buildCacheUri(sessionToken, key, DataType.STRING.getValue());
    String insertResponse = sendHttpRequest("POST", insertUri, value);
    assertTrue(insertResponse.contains("successfully") ||
            insertResponse.contains("Success") ||
            insertResponse.contains("inserted") ||
            insertResponse.contains("created"),
        "Insert should succeed: " + insertResponse);

    // READ
    String readUri = buildCacheUri(sessionToken, key, DataType.STRING.getValue());
    String readResponse = sendHttpRequest("GET", readUri, null);
    assertEquals(value, readResponse, "Read should return the original value");

    // UPDATE
    String updateResponse = sendHttpRequest("PUT", insertUri, updatedValue);
    assertTrue(updateResponse.contains("successfully") ||
            insertResponse.contains("Success") ||
            updateResponse.contains("updated"),
        "Update should succeed: " + updateResponse);

    // READ after UPDATE
    String readAfterUpdate = sendHttpRequest("GET", readUri, null);
    assertEquals(updatedValue, readAfterUpdate, "Read after update should return new value");

    // DELETE
    String deleteResponse = sendHttpRequest("DELETE", readUri, null);
    assertTrue(deleteResponse.contains("successfully") ||
            deleteResponse.contains("Success") ||
            deleteResponse.contains("deleted"),
        "Delete should succeed: " + deleteResponse);

    // READ after DELETE (ожидаем ошибку или сообщение о отсутствии)
    String readAfterDelete = sendHttpRequest("GET", readUri, null);
    assertTrue(readAfterDelete.contains("not found") ||
            readAfterDelete.contains("Not found") ||
            readAfterDelete.contains("No data") ||
            readAfterDelete.contains("does not exist"),
        "Read after delete should return not found: " + readAfterDelete);
  }

  @Test
  void testJsonOperations() throws Exception {
    // Создаем пользователя для теста
    String testUser = "json_test_user_" + System.currentTimeMillis();
    String sessionToken = createStorageAndGetSession(testUser, "testpass");
    assertNotNull(sessionToken, "Need valid session token for JSON operations");

    String key = "json_key_" + System.currentTimeMillis();
    String validJson = "{\"name\":\"John\",\"age\":30,\"city\":\"New York\"}";
    String invalidJson = "invalid json {";

    // INSERT valid JSON
    String insertUri = buildCacheUri(sessionToken, key, DataType.JSON.getValue());
    String insertResponse = sendHttpRequest("POST", insertUri, validJson);
    assertTrue(insertResponse.contains("successfully") || insertResponse.contains("Success"),
        "Valid JSON insert should succeed: " + insertResponse);

    // READ JSON
    String readUri = buildCacheUri(sessionToken, key, DataType.JSON.getValue());
    String readResponse = sendHttpRequest("GET", readUri, null);
    assertEquals(validJson, readResponse, "Read should return the original JSON");

    // INSERT invalid JSON
    String invalidResponse = sendHttpRequest("POST", insertUri, invalidJson);
    assertTrue(invalidResponse.contains("Invalid data format") ||
            invalidResponse.contains("Bad Request") ||
            invalidResponse.contains("Invalid JSON"),
        "Invalid JSON should be rejected: " + invalidResponse);
  }

  @Test
  void testBytesOperations() throws Exception {
    // Создаем пользователя для теста
    String testUser = "bytes_test_user_" + System.currentTimeMillis();
    String sessionToken = createStorageAndGetSession(testUser, "testpass");
    assertNotNull(sessionToken, "Need valid session token for bytes operations");

    String key = "bytes_key_" + System.currentTimeMillis();
    String validBase64 = "SGVsbG8gV29ybGQ=";
    String invalidBase64 = "invalid base64!!";

    // INSERT valid Base64
    String insertUri = buildCacheUri(sessionToken, key, DataType.BYTES.getValue());
    String insertResponse = sendHttpRequest("POST", insertUri, validBase64);
    assertTrue(insertResponse.contains("successfully") || insertResponse.contains("Success"),
        "Valid Base64 insert should succeed: " + insertResponse);

    // READ Base64
    String readUri = buildCacheUri(sessionToken, key, DataType.BYTES.getValue());
    String readResponse = sendHttpRequest("GET", readUri, null);
    assertEquals(validBase64, readResponse, "Read should return the original Base64");

    // INSERT invalid Base64
    String invalidResponse = sendHttpRequest("POST", insertUri, invalidBase64);
    assertTrue(invalidResponse.contains("Invalid data format") ||
            invalidResponse.contains("Bad Request") ||
            invalidResponse.contains("Invalid Base64"),
        "Invalid Base64 should be rejected: " + invalidResponse);
  }
}