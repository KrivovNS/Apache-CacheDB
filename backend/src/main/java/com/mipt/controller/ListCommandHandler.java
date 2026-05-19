package com.mipt.controller;

import com.mipt.cache.ListCache;
import com.mipt.cache.CacheResult;

import java.util.List;

public class ListCommandHandler {

  private final ListCache listCache;

  public ListCommandHandler(ListCache listCache) {
    this.listCache = listCache;
  }

  public ListCache getListCache() {
    return listCache;
  }

  /**
   * Execute LPUSH command: push value to left of list
   */
  public CacheResult lpush(String key, String value) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }
    if (value == null) {
      return CacheResult.error("Value cannot be null");
    }

    try {
      listCache.lpush(key, value);
      int size = listCache.llen(key);
      return CacheResult.success("Pushed to left. List size: " + size);
    } catch (Exception e) {
      return CacheResult.error("LPUSH error: " + e.getMessage());
    }
  }

  /**
   * Execute RPUSH command: push value to right of list
   */
  public CacheResult rpush(String key, String value) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }
    if (value == null) {
      return CacheResult.error("Value cannot be null");
    }

    try {
      listCache.rpush(key, value);
      int size = listCache.llen(key);
      return CacheResult.success("Pushed to right. List size: " + size);
    } catch (Exception e) {
      return CacheResult.error("RPUSH error: " + e.getMessage());
    }
  }

  /**
   * Execute LPOP command: pop value from left of list
   */
  public CacheResult lpop(String key) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      Object value = listCache.lpop(key);
      if (value == null) {
        return CacheResult.error("List is empty or does not exist");
      }
      return CacheResult.success("Popped: " + value);
    } catch (Exception e) {
      return CacheResult.error("LPOP error: " + e.getMessage());
    }
  }

  /**
   * Execute RPOP command: pop value from right of list
   */
  public CacheResult rpop(String key) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      Object value = listCache.rpop(key);
      if (value == null) {
        return CacheResult.error("List is empty or does not exist");
      }
      return CacheResult.success("Popped: " + value);
    } catch (Exception e) {
      return CacheResult.error("RPOP error: " + e.getMessage());
    }
  }

  /**
   * Execute LRANGE command: get range of list
   */
  public CacheResult lrange(String key, int start, int end) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      List<Object> range = listCache.lrange(key, start, end);
      if (range.isEmpty()) {
        return CacheResult.error("List is empty or range invalid");
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < range.size(); i++) {
        sb.append("[").append(start + i).append("] ").append(range.get(i)).append("\n");
      }
      return CacheResult.success(sb.toString());
    } catch (Exception e) {
      return CacheResult.error("LRANGE error: " + e.getMessage());
    }
  }

  /**
   * Execute LLEN command: get size of list
   */
  public CacheResult llen(String key) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      int size = listCache.llen(key);
      return CacheResult.success("List size: " + size);
    } catch (Exception e) {
      return CacheResult.error("LLEN error: " + e.getMessage());
    }
  }

  /**
   * Execute LINDEX command: get value at index
   */
  public CacheResult lindex(String key, int index) {
    if (key == null || key.trim().isEmpty()) {
      return CacheResult.error("Key cannot be empty");
    }

    try {
      Object value = listCache.lindex(key, index);
      if (value == null) {
        return CacheResult.error("Index out of range or list empty");
      }
      return CacheResult.success("Value at index " + index + ": " + value);
    } catch (Exception e) {
      return CacheResult.error("LINDEX error: " + e.getMessage());
    }
  }
}