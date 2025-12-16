package com.mipt;

import com.mipt.controller.DataTypeValidator;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

public class DataTypeValidatorTest {

  @Test
  void testValidateJSON_Success() {
    String validJson = "{\"name\": \"John\", \"age\": 30}";
    assertTrue(DataTypeValidator.validateDataByType(validJson, "json"));
  }

  @Test
  void testValidateJSON_Invalid() {
    String invalidJson = "{\"name\": \"John\", \"age\": 30} extra text";
    assertFalse(DataTypeValidator.validateDataByType(invalidJson, "json"));
  }

  @Test
  void testValidateJSON_EmptyObject() {
    String emptyJson = "{}";
    assertTrue(DataTypeValidator.validateDataByType(emptyJson, "json"));
  }

  @Test
  void testValidateJSON_Array() {
    String arrayJson = "[1, 2, 3]";
    assertTrue(DataTypeValidator.validateDataByType(arrayJson, "json"));
  }

  @Test
  void testValidateBytes_Base64() {
    String base64Data = "SGVsbG8gV29ybGQ=";
    assertTrue(DataTypeValidator.validateDataByType(base64Data, "byte[]"));
  }

  @Test
  void testValidateBytes_Hex() {
    String hexData = "48656c6c6f20576f726c64";
    assertTrue(DataTypeValidator.validateDataByType(hexData, "byte[]"));
  }

  @Test
  void testValidateBytes_HexWith0x() {
    String hexData = "0x48656c6c6f20576f726c64";
    assertTrue(DataTypeValidator.validateDataByType(hexData, "byte[]"));
  }

  @Test
  void testValidateBytes_Invalid() {
    String invalidBase64 = "NotBase64!@#$";
    assertFalse(DataTypeValidator.validateDataByType(invalidBase64, "byte[]"));
  }

  @Test
  void testValidateString_AlwaysTrue() {
    String text = "Any text here";
    assertTrue(DataTypeValidator.validateDataByType(text, "string"));
  }

  @Test
  void testValidateString_Empty() {
    String empty = "";
    assertFalse(DataTypeValidator.validateDataByType(empty, "string"));
  }

  @Test
  void testProcessDataForStorage_JSON() {
    String json = "{\"test\": \"value\"}";
    Object processed = DataTypeValidator.processDataForStorage(json, "json");
    assertEquals(json, processed);
  }

  @Test
  void testProcessDataForStorage_Bytes_Base64() {
    String base64 = "SGVsbG8=";
    Object processed = DataTypeValidator.processDataForStorage(base64, "byte[]");
    assertTrue(processed instanceof byte[]);
    byte[] bytes = (byte[]) processed;
    assertEquals("Hello", new String(bytes, StandardCharsets.UTF_8));
  }

  @Test
  void testProcessDataForStorage_Bytes_Hex() {
    String hex = "48656c6c6f";
    Object processed = DataTypeValidator.processDataForStorage(hex, "byte[]");
    assertTrue(processed instanceof byte[]);
    byte[] bytes = (byte[]) processed;
    assertEquals("Hello", new String(bytes, StandardCharsets.UTF_8));
  }

  @Test
  void testProcessDataForStorage_String() {
    String text = "Simple text";
    Object processed = DataTypeValidator.processDataForStorage(text, "string");
    assertEquals(text, processed);
  }

  @Test
  void testFormatDataForResponse_JSON() {
    String json = "{\"key\": \"value\"}";
    String formatted = DataTypeValidator.formatDataForResponse(json, "json");
    assertEquals(json, formatted);
  }

  @Test
  void testFormatDataForResponse_Bytes() {
    byte[] bytes = "Test".getBytes(StandardCharsets.UTF_8);
    String formatted = DataTypeValidator.formatDataForResponse(bytes, "byte[]");
    assertEquals("VGVzdA==", formatted);
  }

  @Test
  void testFormatDataForResponse_BytesFromString() {
    String base64String = "VGVzdA==";
    String formatted = DataTypeValidator.formatDataForResponse(base64String, "byte[]");
    assertEquals("VGVzdA==", formatted);
  }

  @Test
  void testFormatDataForResponse_String() {
    String text = "Hello World";
    String formatted = DataTypeValidator.formatDataForResponse(text, "string");
    assertEquals(text, formatted);
  }

  @Test
  void testHexStringToByteArray() throws Exception {
    var method = DataTypeValidator.class.getDeclaredMethod("hexStringToByteArray", String.class);
    method.setAccessible(true);

    String hex = "48656c6c6f";
    byte[] result = (byte[]) method.invoke(null, hex);

    assertEquals("Hello", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  void testIsValidHexString() throws Exception {
    var method = DataTypeValidator.class.getDeclaredMethod("isValidHexString", String.class);
    method.setAccessible(true);

    // Valid cases
    assertTrue((Boolean) method.invoke(null, "48656c6c6f"));
    assertTrue((Boolean) method.invoke(null, "0x48656c6c6f"));

    // Invalid cases
    assertThrows(Exception.class, () -> method.invoke(null, "48656c6c6f1"));
    assertThrows(Exception.class, () -> method.invoke(null, "GG"));
  }
}