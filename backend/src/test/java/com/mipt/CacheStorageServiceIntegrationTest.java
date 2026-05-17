package com.mipt;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.MaxMemoryPolicy;
import com.mipt.service.CacheStorageService;
import com.mipt.utils.TestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CacheStorageServiceIntegrationTest {

  @Test
  void testPostAndGet_Success() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        10 * 1024 * 1024,
        false
    );

    CacheResult postResult = service.post(
        "testKey",
        "testValue",
        DataType.STRING,
        "testUser",
        null
    );

    assertTrue(postResult.isSuccess());

    CacheResult getResult = service.get("testKey");
    assertTrue(getResult.isSuccess());
    assertEquals("testValue", getResult.getData());
  }

  @Test
  void testPost_DuplicateKey() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        10 * 1024 * 1024,
        false
    );

    service.post("key", "value1", DataType.STRING, "user", null);

    CacheResult duplicateResult = service.post(
        "key", "value2", DataType.STRING, "user", null
    );

    assertFalse(duplicateResult.isSuccess());
    assertTrue(duplicateResult.getMessage().contains("already exists"));
  }

  @Test
  void testPut_NonExistentKey() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        10 * 1024 * 1024,
        false
    );

    CacheResult putResult = service.put(
        "nonExistentKey",
        "value",
        DataType.STRING,
        "user",
        null
    );

    assertFalse(putResult.isSuccess());
    assertTrue(putResult.getMessage().contains("not exists"));
  }

  @Test
  void testPostPutGet_Sequence() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        10 * 1024 * 1024,
        false
    );

    // POST
    CacheResult postResult = service.post(
        "key", "initial", DataType.STRING, "user", null
    );
    assertTrue(postResult.isSuccess());
    assertEquals("initial", service.get("key").getData());

    // PUT
    CacheResult putResult = service.put(
        "key", "updated", DataType.STRING, "user", null
    );
    assertTrue(putResult.isSuccess());
    assertEquals("updated", service.get("key").getData());

    // PUT с TTL
    CacheResult putWithTTL = service.put(
        "key", "withTTL", DataType.STRING, "user", 5L
    );
    assertTrue(putWithTTL.isSuccess());

    // GET
    assertEquals("withTTL", service.get("key").getData());
  }
}