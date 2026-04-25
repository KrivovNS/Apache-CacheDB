package com.mipt;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.MaxMemoryPolicy;
import com.mipt.service.CacheStorageService;
import com.mipt.utils.TestUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EvictionPolicyTest {

  private static final long SMALL_MEMORY = 1024 * 1024;

  @Test
  void testNOEVICTION_Policy() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.NOEVICTION,
        SMALL_MEMORY,
        false
    );

    long entrySize = SMALL_MEMORY / 2 - 100;
    CacheResult result1 = service.post("key1", sizedValue("key1", entrySize), DataType.STRING, "user", null);
    assertTrue(result1.isSuccess());

    CacheResult result2 = service.post("key2", sizedValue("key2", entrySize), DataType.STRING, "user", null);
    assertTrue(result2.isSuccess());

    CacheResult result3 = service.post("key3", sizedValue("key3", 1000), DataType.STRING, "user", null);
    assertFalse(result3.isSuccess());
    assertTrue(result3.getMessage().contains("Memory overflow")
        || result3.getMessage().contains("Not enough memory"));
  }

  @Test
  void testALLKEYSLRU_Policy() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        SMALL_MEMORY,
        false
    );

    long entrySize = SMALL_MEMORY / 3;
    service.post("key1", sizedValue("key1", entrySize), DataType.STRING, "user", null);
    service.post("key2", sizedValue("key2", entrySize), DataType.STRING, "user", null);
    service.post("key3", sizedValue("key3", entrySize), DataType.STRING, "user", null);

    assertTrue(service.get("key1").isSuccess());
    assertTrue(service.get("key2").isSuccess());
    assertTrue(service.get("key3").isSuccess());

    service.post("key4", sizedValue("key4", entrySize), DataType.STRING, "user", null);

    assertFalse(service.get("key1").isSuccess());
    assertTrue(service.get("key2").isSuccess());
    assertTrue(service.get("key3").isSuccess());
    assertTrue(service.get("key4").isSuccess());

    service.get("key2");

    service.post("key5", sizedValue("key5", entrySize), DataType.STRING, "user", null);

    assertFalse(service.get("key3").isSuccess());
    assertTrue(service.get("key2").isSuccess());
    assertTrue(service.get("key4").isSuccess());
    assertTrue(service.get("key5").isSuccess());
  }

  @Test
  void testVOLATILELRU_Policy() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.VOLATILELRU,
        SMALL_MEMORY,
        false
    );

    long entrySize = SMALL_MEMORY / 3;
    service.post("volatile1", sizedValue("volatile1", entrySize), DataType.STRING, "user", 10L);
    service.post("persistent1", sizedValue("persistent1", entrySize), DataType.STRING, "user", null);
    service.post("volatile2", sizedValue("volatile2", entrySize), DataType.STRING, "user", 10L);

    service.post("key4", sizedValue("key4", entrySize), DataType.STRING, "user", null);

    int volatileCount = 0;
    if (service.get("volatile1").isSuccess()) {
      volatileCount++;
    }
    if (service.get("volatile2").isSuccess()) {
      volatileCount++;
    }

    assertEquals(1, volatileCount);
    assertTrue(service.get("persistent1").isSuccess());
    assertTrue(service.get("key4").isSuccess());
  }

  @Test
  void testALLKEYSLFU_Policy() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLFU,
        SMALL_MEMORY,
        false
    );

    long entrySize = SMALL_MEMORY / 3;
    service.post("key1", sizedValue("key1", entrySize), DataType.STRING, "user", null);
    service.post("key2", sizedValue("key2", entrySize), DataType.STRING, "user", null);
    service.post("key3", sizedValue("key3", entrySize), DataType.STRING, "user", null);

    service.get("key1");
    service.get("key1");
    service.get("key1");
    service.get("key2");

    service.post("key4", sizedValue("key4", entrySize), DataType.STRING, "user", null);

    assertFalse(service.get("key3").isSuccess());
    assertTrue(service.get("key1").isSuccess());
    assertTrue(service.get("key2").isSuccess());
    assertTrue(service.get("key4").isSuccess());
  }

  @Test
  void testVOLATILELFU_Policy() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.VOLATILELFU,
        SMALL_MEMORY,
        false
    );

    long entrySize = SMALL_MEMORY / 3;
    service.post("volatile1", sizedValue("volatile1", entrySize), DataType.STRING, "user", 10L);
    service.post("persistent1", sizedValue("persistent1", entrySize), DataType.STRING, "user", null);
    service.post("volatile2", sizedValue("volatile2", entrySize), DataType.STRING, "user", 10L);

    service.get("volatile1");
    service.get("volatile1");

    service.post("key4", sizedValue("key4", entrySize), DataType.STRING, "user", null);

    assertFalse(service.get("volatile2").isSuccess());
    assertTrue(service.get("volatile1").isSuccess());
    assertTrue(service.get("persistent1").isSuccess());
    assertTrue(service.get("key4").isSuccess());
  }

  @Test
  void testMemoryCalculation_PutUpdate() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        SMALL_MEMORY,
        false
    );

    String initialValue = sizedValue("key", SMALL_MEMORY / 2);
    String updatedValue = sizedValue("key", SMALL_MEMORY / 4);

    service.post("key", initialValue, DataType.STRING, "user", null);
    service.put("key", updatedValue, DataType.STRING, "user", null);

    assertTrue(service.get("key").isSuccess());
    assertEquals(updatedValue, service.get("key").getData());

    service.post("key2", sizedValue("key2", SMALL_MEMORY / 2 - 1024), DataType.STRING, "user", null);
    assertTrue(service.get("key2").isSuccess());
  }

  @Test
  void testConcurrentAccessWithEviction() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        100 * 1024,
        false
    );

    final int numThreads = 10;
    final int operationsPerThread = 50;
    final AtomicInteger successfulOperations = new AtomicInteger(0);
    final AtomicInteger keyExistsErrors = new AtomicInteger(0);
    final AtomicInteger memoryErrors = new AtomicInteger(0);
    final AtomicInteger otherErrors = new AtomicInteger(0);

    List<Thread> threads = new ArrayList<>();

    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      Thread thread = new Thread(() -> {
        for (int j = 0; j < operationsPerThread; j++) {
          String key = "key-" + threadId + "-" + j;
          String value = "value-" + threadId + "-" + j;

          try {
            CacheResult result = service.post(key, value, DataType.STRING, "user", null);

            if (result.isSuccess()) {
              successfulOperations.incrementAndGet();

              if (j > 0 && Math.random() > 0.7) {
                service.get("key-" + threadId + "-" + (j - 1));
              }
            } else {
              String message = result.getMessage();
              if (message.contains("already exists")) {
                keyExistsErrors.incrementAndGet();
              } else if (message.contains("Not enough memory")
                  || message.contains("Memory overflow")) {
                memoryErrors.incrementAndGet();
              } else {
                otherErrors.incrementAndGet();
              }
            }
          } catch (Exception e) {
            System.err.println("Thread " + threadId + " error: " + e.getMessage());
          }
        }
      });
      threads.add(thread);
    }

    for (Thread thread : threads) {
      thread.start();
    }

    for (Thread thread : threads) {
      try {
        thread.join(5000);
      } catch (InterruptedException e) {
        thread.interrupt();
      }
    }

    System.out.println("=== Concurrent Test Results ===");
    System.out.println("Successful operations: " + successfulOperations.get());
    System.out.println("Key exists errors: " + keyExistsErrors.get());
    System.out.println("Memory errors: " + memoryErrors.get());
    System.out.println("Other errors: " + otherErrors.get());
    System.out.println("Total attempts: " + (numThreads * operationsPerThread));

    assertTrue(successfulOperations.get() > 0,
        "There should be at least one successful operation");

    service.post("final-key", "final-value", DataType.STRING, "user", null);
    assertTrue(service.get("final-key").isSuccess(),
        "Service should continue working after concurrent access");
  }

  private String sizedValue(String key, long targetEntryBytes) {
    int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
    int payloadBytes = (int) Math.max(1, targetEntryBytes - keyBytes);
    return "x".repeat(payloadBytes);
  }
}
