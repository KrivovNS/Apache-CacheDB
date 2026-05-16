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
        cacheService = new CacheStorageService() {
            @Override
            public CacheResult post(String key, Object value, DataType dataType,
                    String user, Long ttlSeconds, long sizeBytes) {
                return super.post(key, value, dataType, user, ttlSeconds, sizeBytes);
            }
        };
    }

    @Test
    void testTTL_Expiration() throws InterruptedException {
        // Устанавливаем ключ с TTL 1 секунда
        CacheResult result = cacheService.post(
                "tempKey",
                "test value",
                DataType.STRING,
                "testUser",
                1L,
                100L
        );

        assertTrue(result.isSuccess());

        // Проверяем, что ключ доступен сразу
        CacheResult getResult = cacheService.get("tempKey");
        assertTrue(getResult.isSuccess());
        assertEquals("test value", getResult.getData());

        // Ждем 1.5 секунды
        TimeUnit.MILLISECONDS.sleep(1500);

        // Ключ должен исчезнуть
        CacheResult expiredResult = cacheService.get("tempKey");
        assertFalse(expiredResult.isSuccess());
        assertEquals("Key expired", expiredResult.getMessage());
    }

    @Test
    void testTTL_NoExpiration() throws InterruptedException {
        // Устанавливаем ключ без TTL
        CacheResult result = cacheService.post(
                "persistentKey",
                "persistent value",
                DataType.STRING,
                "testUser",
                null, // Без TTL
                100L
        );

        assertTrue(result.isSuccess());

        // Ждем 2 секунды
        TimeUnit.MILLISECONDS.sleep(2000);

        // Ключ должен быть все еще доступен
        CacheResult getResult = cacheService.get("persistentKey");
        assertTrue(getResult.isSuccess());
        assertEquals("persistent value", getResult.getData());
    }

    @Test
    void testTTL_CleanupExpired() {
        // Устанавливаем несколько ключей с разными TTL
        cacheService.post("key1", "value1", DataType.STRING, "user", 1L, 100L);
        cacheService.post("key2", "value2", DataType.STRING, "user", 2L, 100L);
        cacheService.post("key3", "value3", DataType.STRING, "user", null, 100L);

        try {
            TimeUnit.MILLISECONDS.sleep(1500);

            // Вызываем cleanup
            cacheService.get("key1");

            // key1 должен быть удален
            assertFalse(cacheService.get("key1").isSuccess());
            // key2 и key3 должны остаться
            assertTrue(cacheService.get("key2").isSuccess());
            assertTrue(cacheService.get("key3").isSuccess());

            TimeUnit.MILLISECONDS.sleep(600);

            // Теперь key2 тоже должен быть удален
            cacheService.get("key2");
            assertFalse(cacheService.get("key2").isSuccess());

            // key3 должен остаться
            assertTrue(cacheService.get("key3").isSuccess());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testTTL_UpdateWithNewTTL() {
        // Создаем ключ без TTL
        cacheService.post("key", "value1", DataType.STRING, "user", null, 100L);

        // Обновляем с TTL
        cacheService.put("key", "value2", DataType.STRING, "user", 1L, 100L);

        // Должен быть доступен
        assertTrue(cacheService.get("key").isSuccess());

        try {
            TimeUnit.MILLISECONDS.sleep(1500);

            // Должен истечь
            assertFalse(cacheService.get("key").isSuccess());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testTTL_RemoveTTL() {
        // Создаем ключ с TTL
        cacheService.post("key", "value1", DataType.STRING, "user", 1L, 100L);

        // Обновляем без TTL
        cacheService.put("key", "value2", DataType.STRING, "user", null, 100L);

        try {
            TimeUnit.MILLISECONDS.sleep(1500);

            // Должен остаться
            assertTrue(cacheService.get("key").isSuccess());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}