package com.mipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mipt.cache.Cache;
import com.mipt.cache.CacheResult;
import com.mipt.controller.MemoryCalculator;
import com.mipt.model.CacheEntry;
import com.mipt.model.CacheStorage;
import com.mipt.model.DataType;
import com.mipt.model.MaxMemoryPolicy;
import com.mipt.service.CacheStorageService;
import com.mipt.utils.TestUtils;
import org.junit.jupiter.api.Test;

class InMemoryCompressionIntegrationTest {

  @Test
  void shouldStoreCompressedPayloadInMemoryAndReturnOriginalValue() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        20 * 1024 * 1024,
        false
    );

    String value = "A".repeat(20_000);
    CacheResult postResult = service.post("compressed-key", value, DataType.STRING, "user", null, 0);
    assertTrue(postResult.isSuccess());

    CacheStorage storage = (CacheStorage) TestUtils.getPrivateField(service, "database");
    Cache mainCache = (Cache) TestUtils.getPrivateField(storage, "mainCache");
    CacheEntry storedEntry = (CacheEntry) mainCache.get("compressed-key");

    assertTrue(storedEntry.getData() instanceof byte[]);

    long rawSize = MemoryCalculator.calculateEntrySizeBytes("compressed-key", DataType.STRING, value);
    assertEquals(rawSize, storedEntry.getSizeInBytes());
    assertTrue(((byte[]) storedEntry.getData()).length < value.length());

    CacheResult getResult = service.get("compressed-key");
    assertTrue(getResult.isSuccess());
    assertEquals(value, getResult.getData());
  }
}
