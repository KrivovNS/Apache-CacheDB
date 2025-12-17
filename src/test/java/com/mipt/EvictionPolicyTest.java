package com.mipt;

import com.mipt.cache.CacheResult;
import com.mipt.model.DataType;
import com.mipt.model.MaxMemoryPolicy;
import com.mipt.service.CacheStorageService;
import com.mipt.utils.TestUtils;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class EvictionPolicyTest {

  private static final long SMALL_MEMORY = 1024 * 1024;

  @Test
  void testNOEVICTION_Policy() throws Exception {
    // NOEVICTION
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.NOEVICTION,
        SMALL_MEMORY,
        false
    );

    long entrySize = SMALL_MEMORY / 2 - 100;
    CacheResult result1 = service.post("key1", "value1", DataType.STRING,
        "user", null, entrySize);
    assertTrue(result1.isSuccess());

    CacheResult result2 = service.post("key2", "value2", DataType.STRING,
        "user", null, entrySize);
    assertTrue(result2.isSuccess());

    // Попытка добавить третий элемент
    CacheResult result3 = service.post("key3", "value3", DataType.STRING,
        "user", null, 1000);
    assertFalse(result3.isSuccess());
    assertTrue(result3.getMessage().contains("Memory overflow") ||
        result3.getMessage().contains("Not enough memory"));
  }

  @Test
  void testALLKEYSLRU_Policy() throws Exception {
    CacheStorageService service = TestUtils.createTestService(
        MaxMemoryPolicy.ALLKEYSLRU,
        SMALL_MEMORY,
        false
    );

    // Заполняем всю память тремя элементами
    long entrySize = SMALL_MEMORY / 3;
    service.post("key1", "value1", DataType.STRING, "user", null, entrySize);
    service.post("key2", "value2", DataType.STRING, "user", null, entrySize);
    service.post("key3", "value3", DataType.STRING, "user", null, entrySize);

    assertTrue(service.get("key1").isSuccess());
    assertTrue(service.get("key2").isSuccess());
    assertTrue(service.get("key3").isSuccess());

    // Добавляем четвертый элемент - должен вытеснить LRU
    service.post("key4", "value4", DataType.STRING, "user", null, entrySize);

    // key1 должен быть вытеснен
    assertFalse(service.get("key1").isSuccess());

    assertTrue(service.get("key2").isSuccess());
    assertTrue(service.get("key3").isSuccess());
    assertTrue(service.get("key4").isSuccess());

    service.get("key2");

    // Добавляем пятый элемент - должен вытеснить key3
    service.post("key5", "value5", DataType.STRING, "user", null, entrySize);

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

    // Создаем смесь элементов
    service.post("volatile1", "value1", DataType.STRING, "user", 10L, SMALL_MEMORY / 3);
    service.post("persistent1", "value2", DataType.STRING, "user", null, SMALL_MEMORY / 3);
    service.post("volatile2", "value3", DataType.STRING, "user", 10L, SMALL_MEMORY / 3);

    // Добавляем четвертый элемент - должен вытеснить volatile элемент
    service.post("key4", "value4", DataType.STRING, "user", null, SMALL_MEMORY / 3);

    int volatileCount = 0;
    if (service.get("volatile1").isSuccess()) {
      volatileCount++;
    }
    if (service.get("volatile2").isSuccess()) {
      volatileCount++;
    }

    assertEquals(1, volatileCount);

    // persistent1 должен остаться
    assertTrue(service.get("persistent1").isSuccess());
    // key4 должен остаться
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

    // Добавляем три элемента
    service.post("key1", "value1", DataType.STRING, "user", null, entrySize);
    service.post("key2", "value2", DataType.STRING, "user", null, entrySize);
    service.post("key3", "value3", DataType.STRING, "user", null, entrySize);

    service.get("key1");
    service.get("key1");
    service.get("key1");

    service.get("key2");

    // Добавляем четвертый элемент - должен вытеснить key3 (наименее используемый)
    service.post("key4", "value4", DataType.STRING, "user", null, entrySize);

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

    // Создаем смесь элементов
    service.post("volatile1", "value1", DataType.STRING, "user", 10L, entrySize);
    service.post("persistent1", "value2", DataType.STRING, "user", null, entrySize);
    service.post("volatile2", "value3", DataType.STRING, "user", 10L, entrySize);

    service.get("volatile1");
    service.get("volatile1");

    // Добавляем четвертый элемент - должен вытеснить volatile2 (наименее используемый volatile)
    service.post("key4", "value4", DataType.STRING, "user", null, entrySize);

    // volatile2 должен быть вытеснен
    assertFalse(service.get("volatile2").isSuccess());

    assertTrue(service.get("volatile1").isSuccess());

    // persistent1 и key4 должны остаться
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

    long initialSize = SMALL_MEMORY / 2;
    long updatedSize = SMALL_MEMORY / 4;

    // Добавляем элемент через POST
    service.post("key", "initial value", DataType.STRING, "user", null, initialSize);

    // Обновляем элемент через PUT с меньшим размером
    service.put("key", "updated value", DataType.STRING, "user", null, updatedSize);

    // Должен остаться в памяти
    assertTrue(service.get("key").isSuccess());
    assertEquals("updated value", service.get("key").getData());

    // Проверяем, что можно добавить больше элементов (память освобождена)
    service.post("key2", "value2", DataType.STRING, "user", null,
        SMALL_MEMORY - updatedSize - 100);
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
            CacheResult result = service.post(key, value, DataType.STRING,
                "user", null, 512);

            if (result.isSuccess()) {
              successfulOperations.incrementAndGet();

              if (j > 0 && Math.random() > 0.7) {
                service.get("key-" + threadId + "-" + (j - 1));
              }
            } else {
              String message = result.getMessage();
              if (message.contains("already exists")) {
                keyExistsErrors.incrementAndGet();
              } else if (message.contains("Not enough memory") ||
                  message.contains("Memory overflow")) {
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

    // Запускаем все потоки
    for (Thread thread : threads) {
      thread.start();
    }

    // Ждем завершения с таймаутом
    for (Thread thread : threads) {
      try {
        thread.join(5000);
      } catch (InterruptedException e) {
        thread.interrupt();
      }
    }

    // Выводим статистику
    System.out.println("=== Concurrent Test Results ===");
    System.out.println("Successful operations: " + successfulOperations.get());
    System.out.println("Key exists errors: " + keyExistsErrors.get());
    System.out.println("Memory errors: " + memoryErrors.get());
    System.out.println("Other errors: " + otherErrors.get());
    System.out.println("Total attempts: " + (numThreads * operationsPerThread));

    assertTrue(successfulOperations.get() > 0,
        "Должна быть хотя бы одна успешная операция");

    // Проверяем, что сервис не сломался
    service.post("final-key", "final-value", DataType.STRING, "user", null, 100);
    assertTrue(service.get("final-key").isSuccess(),
        "Сервис должен продолжать работать после конкурентного доступа");
  }
}
