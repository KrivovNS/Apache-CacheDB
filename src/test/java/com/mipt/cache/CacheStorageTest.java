package com.mipt.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheStorageTest {

  @Test
  void testInsertReadDelete() {
    CacheStorage storage = new CacheStorage();

    // Insert
    storage.put("string", "key1", "value1");

    // Check exists
    var readResult = storage.read("string", "key1");
    assertTrue(readResult.isSuccess());
    assertEquals("value1", readResult.getData());

    // Delete
    var deleteResult = storage.delete("string", "key1");
    assertTrue(deleteResult.isSuccess());

    // Check deleted
    var readAfterDelete = storage.read("string", "key1");
    assertFalse(readAfterDelete.isSuccess());
  }
}