package com.mipt;

import com.mipt.model.MaxMemoryPolicy;
import com.mipt.model.PermissionType;
import com.mipt.service.CacheStorageService;
import com.mipt.service.ComandProcessor;
import com.mipt.utils.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComandProcessorTest {

  private ComandProcessor processor;

  @BeforeEach
  void setUp() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        10 * 1024 * 1024,
        false
    );
    processor = new ComandProcessor(service);
  }

  @Test
  void pingAndEcho_ReturnRespFormattedReplies() {
    assertEquals("PONG", processor.process("PING", PermissionType.READER));
    assertEquals("hello", processor.process("ECHO hello", PermissionType.READER));
  }

  @Test
  void setGetDelExists_InlineFormat() {
    assertEquals("OK", processor.process("SET mykey myvalue", PermissionType.ADMIN));
    assertEquals("1", processor.process("EXISTS mykey", PermissionType.READER));
    assertEquals("myvalue", processor.process("GET mykey", PermissionType.READER));
    assertEquals("1", processor.process("DEL mykey", PermissionType.SUPERADMIN));
    assertEquals("nil", processor.process("GET mykey", PermissionType.READER));
  }

  @Test
  void setNxAndXx_MatchCommandBehavior() {
    assertEquals("OK", processor.process("SET key value", PermissionType.ADMIN));
    assertEquals("nil", processor.process("SET key value2 NX", PermissionType.ADMIN));
    assertEquals("nil", processor.process("SET missing value XX", PermissionType.ADMIN));
    assertEquals("OK", processor.process("SET key updated XX", PermissionType.ADMIN));
    assertEquals("updated", processor.process("GET key", PermissionType.READER));
  }

  @Test
  void setWithType_UsesCurrentDataTypes() {
    assertEquals("OK", processor.process("SET profile '{\"name\":\"alice\"}' TYPE json",
        PermissionType.SUPERADMIN));
    assertEquals("{\"name\":\"alice\"}", processor.process("GET profile", PermissionType.READER));

    String invalidTypeResult = processor.process("SET k v TYPE unknown", PermissionType.ADMIN);
    assertTrue(invalidTypeResult.startsWith("ERR unsupported type"));
  }

  @Test
  void invalidStructuredInput_ReturnsUnknownCommandError() {
    String request = "*3\r\n$3\r\nSET\r\n$4\r\nuser\r\n$3\r\nbob\r\n";
    assertEquals("ERR unknown command '*3'", processor.process(request, PermissionType.ADMIN));
  }

  @Test
  void unknownCommand_ReturnsError() {
    assertEquals("ERR unknown command 'NOPE'", processor.process("NOPE", PermissionType.ADMIN));
  }

  @Test
  void readerCannotWrite() {
    assertEquals("ERR READER role can only perform read operations",
        processor.process("SET k v", PermissionType.READER));
    assertEquals("ERR READER role can only perform read operations",
        processor.process("DEL k", PermissionType.READER));
  }
}
