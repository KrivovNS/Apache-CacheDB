package com.mipt.cache;

import java.util.HashMap;
import java.util.Map;

public class SimpleCache implements Cache {
  private final Map<Object, Object> storage;

  public SimpleCache() {
    this.storage = new HashMap<>();
  }

  @Override
  public void put(Object key, Object value) {
    if (key == null || value == null) {
      throw new IllegalArgumentException("Key and value cannot be null");
    }
    storage.put(key, value);
  }

  @Override
  public Object get(Object key) {
    return storage.get(key);
  }

  @Override
  public void remove(Object key) {
    storage.remove(key);
  }

  @Override
  public void clear() {
    storage.clear();
  }

  @Override
  public int size() {
    return storage.size();
  }

  @Override
  public boolean containsKey(Object key) {
    return storage.containsKey(key);
  }
}