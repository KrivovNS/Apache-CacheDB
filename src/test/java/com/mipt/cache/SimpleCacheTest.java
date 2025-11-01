package com.mipt.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SimpleCacheTest {
  private SimpleCache cache;

  @BeforeEach
  public void setUp() {
    cache = new SimpleCache();
  }

  @Test
  public void testPutAndGet() {
    cache.put("key1", "value1");
    cache.put("key2", "value2");

    assertEquals("value1", cache.get("key1"));
    assertEquals("value2", cache.get("key2"));
  }

  @Test
  public void testGetNonExistentKey() {
    assertNull(cache.get("nonExistent"));
  }

  @Test
  public void testRemove() {
    cache.put("key1", "value1");
    cache.put("key2", "value2");

    cache.remove("key1");

    assertNull(cache.get("key1"));
    assertEquals("value2", cache.get("key2"));
    assertEquals(1, cache.size());
  }

  @Test
  public void testClear() {
    cache.put("key1", "value1");
    cache.put("key2", "value2");
    cache.put("key3", "value3");

    cache.clear();

    assertEquals(0, cache.size());
    assertNull(cache.get("key1"));
    assertNull(cache.get("key2"));
    assertNull(cache.get("key3"));
  }

  @Test
  public void testSize() {
    assertEquals(0, cache.size());

    cache.put("key1", "value1");
    assertEquals(1, cache.size());

    cache.put("key2", "value2");
    assertEquals(2, cache.size());

    cache.remove("key1");
    assertEquals(1, cache.size());

    cache.clear();
    assertEquals(0, cache.size());
  }

  @Test
  public void testContainsKey() {
    cache.put("key1", "value1");

    assertTrue(cache.containsKey("key1"));
    assertFalse(cache.containsKey("nonExistent"));
  }

  @Test
  public void testNullKey() {
    assertThrows(IllegalArgumentException.class, () -> {
      cache.put(null, "value");
    });
  }

  @Test
  public void testNullValue() {
    assertThrows(IllegalArgumentException.class, () -> {
      cache.put("key", null);
    });
  }

  @Test
  public void testUpdateExistingKey() {
    cache.put("key1", "value1");
    cache.put("key1", "updatedValue");

    assertEquals("updatedValue", cache.get("key1"));
    assertEquals(1, cache.size());
  }
}
