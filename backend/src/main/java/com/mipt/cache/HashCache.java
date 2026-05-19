package com.mipt.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hash structure cache implementation.
 * Stores key -> (field -> value) mappings.
 */
public class HashCache implements Cache {

  private final Map<Object, Map<Object, Object>> storage;

  public HashCache() {
    this.storage = new ConcurrentHashMap<>();
  }

  @Override
  public void put(Object key, Object value) {
    // Not directly used - use hset instead
    if (!storage.containsKey(key)) {
      storage.put(key, new ConcurrentHashMap<>());
    }
  }

  @Override
  public Object get(Object key) {
    return storage.get(key);
  }

  /**
   * Set field in hash
   */
  public synchronized void hset(Object key, Object field, Object value) {
    storage.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
        .put(field, value);
  }

  /**
   * Get field from hash
   */
  public synchronized Object hget(Object key, Object field) {
    Map<Object, Object> hash = storage.get(key);
    if (hash == null) {
      return null;
    }
    return hash.get(field);
  }

  /**
   * Delete field from hash
   */
  public synchronized Object hdel(Object key, Object field) {
    Map<Object, Object> hash = storage.get(key);
    if (hash == null) {
      return null;
    }
    return hash.remove(field);
  }

  /**
   * Get all fields and values from hash
   */
  public synchronized Map<Object, Object> hgetall(Object key) {
    Map<Object, Object> hash = storage.get(key);
    if (hash == null) {
      return new ConcurrentHashMap<>();
    }
    return new ConcurrentHashMap<>(hash);
  }

  /**
   * Get all field names from hash
   */
  public synchronized Set<Object> hkeys(Object key) {
    Map<Object, Object> hash = storage.get(key);
    if (hash == null) {
      return Set.of();
    }
    return Set.copyOf(hash.keySet());
  }

  /**
   * Check if field exists in hash
   */
  public synchronized boolean hexists(Object key, Object field) {
    Map<Object, Object> hash = storage.get(key);
    if (hash == null) {
      return false;
    }
    return hash.containsKey(field);
  }

  /**
   * Get size of hash (number of fields)
   */
  public synchronized int hlen(Object key) {
    Map<Object, Object> hash = storage.get(key);
    if (hash == null) {
      return 0;
    }
    return hash.size();
  }

  @Override
  public Object freeMemory() {
    // Find a key with smallest hash
    if (storage.isEmpty()) {
      return null;
    }
    return storage.keySet().iterator().next();
  }

  @Override
  public synchronized void remove(Object key) {
    storage.remove(key);
  }

  @Override
  public synchronized void clear() {
    storage.clear();
  }

  @Override
  public synchronized int size() {
    return storage.size();
  }

  @Override
  public synchronized boolean containsKey(Object key) {
    return storage.containsKey(key);
  }

  @Override
  public Set<Object> getKeys() {
    return storage.keySet();
  }

  public boolean containsField(Object key, Object field) {
    Map<Object, Object> hash = storage.get(key);
    return hash != null && hash.containsKey(field);
  }
}