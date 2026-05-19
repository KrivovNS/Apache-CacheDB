package com.mipt.controller;

import com.mipt.cache.HashCache;
import com.mipt.cache.CacheResult;

import java.util.Map;

public class HashCommandHandler {

  private final HashCache hashCache;

  public HashCommandHandler(HashCache hashCache) {
    this.hashCache = hashCache;
  }

  public HashCache getHashCache() {
    return hashCache;
  }

  /**
   * Execute HSET command: set field in hash
   */
  public CacheResult hset(String key, String field, String value) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }
    if (field == null || field.trim().isEmpty()) {
      return CacheResult.error("Field cannot be empty");
    }
    if (value == null) {
      return CacheResult.error("Value cannot be null");
    }

    try {
      hashCache.hset(key, field, value);
      return CacheResult.success("Field set successfully");
    } catch (Exception e) {
      return CacheResult.error("HSET error: " + e.getMessage());
    }
  }

  /**
   * Execute HGET command: get field from hash
   */
  public CacheResult hget(String key, String field) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }
    if (field == null || field.trim().isEmpty()) {
      return CacheResult.error("Field cannot be empty");
    }

    try {
      Object value = hashCache.hget(key, field);
      if (value == null) {
        return CacheResult.error("Field not found");
      }
      return CacheResult.success(value);
    } catch (Exception e) {
      return CacheResult.error("HGET error: " + e.getMessage());
    }
  }

  /**
   * Execute HDEL command: delete field from hash
   */
  public CacheResult hdel(String key, String field) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }
    if (field == null || field.trim().isEmpty()) {
      return CacheResult.error("Field cannot be empty");
    }

    try {
      Object removed = hashCache.hdel(key, field);
      if (removed == null) {
        return CacheResult.error("Field not found");
      }
      return CacheResult.success("Field deleted successfully");
    } catch (Exception e) {
      return CacheResult.error("HDEL error: " + e.getMessage());
    }
  }

  /**
   * Execute HGETALL command: get all fields and values from hash
   */
  public CacheResult hgetall(String key) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      Map<Object, Object> result = hashCache.hgetall(key);
      if (result.isEmpty()) {
        return CacheResult.error("Hash not found or empty");
      }
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<Object, Object> entry : result.entrySet()) {
        sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
      }
      return CacheResult.success(sb.toString());
    } catch (Exception e) {
      return CacheResult.error("HGETALL error: " + e.getMessage());
    }
  }

  /**
   * Execute HKEYS command: get all field names from hash
   */
  public CacheResult hkeys(String key) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      java.util.Set<Object> result = hashCache.hkeys(key);
      if (result.isEmpty()) {
        return CacheResult.error("Hash not found or empty");
      }
      StringBuilder sb = new StringBuilder();
      for (Object field : result) {
        sb.append(field).append("\n");
      }
      return CacheResult.success(sb.toString());
    } catch (Exception e) {
      return CacheResult.error("HKEYS error: " + e.getMessage());
    }
  }

  /**
   * Execute HEXISTS command: check if field exists in hash
   */
  public CacheResult hexists(String key, String field) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }
    if (field == null || field.trim().isEmpty()) {
      return CacheResult.error("Field cannot be empty");
    }

    try {
      boolean exists = hashCache.hexists(key, field);
      if (exists) {
        return CacheResult.success("Field exists");
      }
      return CacheResult.error("Field does not exist");
    } catch (Exception e) {
      return CacheResult.error("HEXISTS error: " + e.getMessage());
    }
  }

  /**
   * Execute HLEN command: get size of hash
   */
  public CacheResult hlen(String key) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      int size = hashCache.hlen(key);
      return CacheResult.success("Hash has " + size + " field(s)");
    } catch (Exception e) {
      return CacheResult.error("HLEN error: " + e.getMessage());
    }
  }
}