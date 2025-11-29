package com.mipt.cache;

import com.mipt.controller.DataType;
import com.mipt.model.CacheStorage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheStorageTest {

  @Test
  void testInsertReadDelete() {
    CacheStorage storage = new CacheStorage();

    // Insert
    storage.post(DataType.STRING, "key1", "value1");

    // Check exists
    var readResult = storage.read(DataType.STRING, "key1");
    assertTrue(readResult.isSuccess());
    assertEquals("value1", readResult.getData());

    // Delete
    var deleteResult = storage.delete(DataType.STRING, "key1");
    assertTrue(deleteResult.isSuccess());

    // Check deleted
    var readAfterDelete = storage.read(DataType.STRING, "key1");
    assertFalse(readAfterDelete.isSuccess());
  }
}