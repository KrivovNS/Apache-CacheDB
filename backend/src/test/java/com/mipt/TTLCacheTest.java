package com.mipt;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.service.CacheStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class TTLCacheTest {

  private CacheStorageService cacheService;

  @BeforeEach
  void setUp() {
    cacheService = new CacheStorageService();
  }

  @Test
  void testTTL_Expiration() throws InterruptedException {
    CacheResult result = cacheService.post(
        "tempKey",
        "test value",
        DataType.STRING,
        "testUser",
        1L
    );

    assertTrue(result.isSuccess());

    CacheResult getResult = cacheService.get("tempKey");
    assertTrue(getResult.isSuccess());
    assertEquals("test value", getResult.getData());

    TimeUnit.MILLISECONDS.sleep(1500);

    CacheResult expiredResult = cacheService.get("tempKey");
    assertFalse(expiredResult.isSuccess());
    assertEquals("Key expired", expiredResult.getMessage());
  }

  @Test
  void testTTL_NoExpiration() throws InterruptedException {
    CacheResult result = cacheService.post(
        "persistentKey",
        "persistent value",
        DataType.STRING,
        "testUser",
        null
    );

    assertTrue(result.isSuccess());

    TimeUnit.MILLISECONDS.sleep(2000);

    CacheResult getResult = cacheService.get("persistentKey");
    assertTrue(getResult.isSuccess());
    assertEquals("persistent value", getResult.getData());
  }

  @Test
  void testTTL_CleanupExpired() {
    cacheService.post("key1", "value1", DataType.STRING, "user", 1L);
    cacheService.post("key2", "value2", DataType.STRING, "user", 2L);
    cacheService.post("key3", "value3", DataType.STRING, "user", null);

    try {
      TimeUnit.MILLISECONDS.sleep(1500);

      cacheService.get("key1");

      assertFalse(cacheService.get("key1").isSuccess());
      assertTrue(cacheService.get("key2").isSuccess());
      assertTrue(cacheService.get("key3").isSuccess());

      TimeUnit.MILLISECONDS.sleep(600);

      cacheService.get("key2");
      assertFalse(cacheService.get("key2").isSuccess());
      assertTrue(cacheService.get("key3").isSuccess());

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void testTTL_UpdateWithNewTTL() {
    cacheService.post("key", "value1", DataType.STRING, "user", null);
    cacheService.put("key", "value2", DataType.STRING, "user", 1L);

    assertTrue(cacheService.get("key").isSuccess());

    try {
      TimeUnit.MILLISECONDS.sleep(1500);
      assertFalse(cacheService.get("key").isSuccess());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void testTTL_RemoveTTL() {
    cacheService.post("key", "value1", DataType.STRING, "user", 1L);
    cacheService.put("key", "value2", DataType.STRING, "user", null);

    try {
      TimeUnit.MILLISECONDS.sleep(1500);
      assertTrue(cacheService.get("key").isSuccess());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}