package com.mipt.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * List structure cache implementation.
 * Stores key -> List of values.
 */
public class ListCache implements Cache {

  private final Map<Object, List<Object>> storage;

  public ListCache() {
    this.storage = new ConcurrentHashMap<>();
  }

  @Override
  public void put(Object key, Object value) {
    // Not directly used - use lpush/rpush instead
    if (!storage.containsKey(key)) {
      storage.put(key, new CopyOnWriteArrayList<>());
    }
  }

  @Override
  public Object get(Object key) {
    return storage.get(key);
  }

  /**
   * Push value to left (head) of list
   */
  public synchronized void lpush(Object key, Object value) {
    storage.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
        .add(0, value);
  }

  /**
   * Push value to right (tail) of list
   */
  public synchronized void rpush(Object key, Object value) {
    storage.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
        .add(value);
  }

  /**
   * Pop value from left (head) of list
   */
  public synchronized Object lpop(Object key) {
    List<Object> list = storage.get(key);
    if (list == null || list.isEmpty()) {
      return null;
    }
    return list.remove(0);
  }

  /**
   * Pop value from right (tail) of list
   */
  public synchronized Object rpop(Object key) {
    List<Object> list = storage.get(key);
    if (list == null || list.isEmpty()) {
      return null;
    }
    return list.remove(list.size() - 1);
  }

  /**
   * Get range of list from start to end (inclusive)
   */
  public synchronized List<Object> lrange(Object key, int start, int end) {
    List<Object> list = storage.get(key);
    if (list == null || list.isEmpty()) {
      return new ArrayList<>();
    }

    int size = list.size();
    int from = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
    int to = end < 0 ? size + end : Math.min(end, size - 1);

    if (from > to || from >= size || to < 0) {
      return new ArrayList<>();
    }

    return new ArrayList<>(list.subList(from, to + 1));
  }

  /**
   * Get size of list
   */
  public synchronized int llen(Object key) {
    List<Object> list = storage.get(key);
    if (list == null) {
      return 0;
    }
    return list.size();
  }

  /**
   * Get value at index
   */
  public synchronized Object lindex(Object key, int index) {
    List<Object> list = storage.get(key);
    if (list == null) {
      return null;
    }
    if (index < 0) {
      index = list.size() + index;
    }
    if (index < 0 || index >= list.size()) {
      return null;
    }
    return list.get(index);
  }

  /**
   * Set value at index
   */
  public synchronized void lset(Object key, int index, Object value) {
    List<Object> list = storage.get(key);
    if (list == null) {
      throw new IllegalArgumentException("Key does not exist");
    }
    int actualIndex = index < 0 ? list.size() + index : index;
    if (actualIndex < 0 || actualIndex >= list.size()) {
      throw new IndexOutOfBoundsException("Index out of range");
    }
    list.set(actualIndex, value);
  }

  /**
   * Trim list to keep only elements from start to end
   */
  public synchronized void ltrim(Object key, int start, int end) {
    List<Object> list = storage.get(key);
    if (list == null || list.isEmpty()) {
      return;
    }

    int size = list.size();
    int from = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
    int to = end < 0 ? size + end : Math.min(end, size - 1);

    if (from > to || from >= size || to < 0) {
      storage.remove(key);
      return;
    }

    List<Object> trimmed = new CopyOnWriteArrayList<>(list.subList(from, to + 1));
    storage.put(key, trimmed);
  }

  @Override
  public Object freeMemory() {
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
}