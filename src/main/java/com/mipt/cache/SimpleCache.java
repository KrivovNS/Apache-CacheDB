package com.mipt.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleCache implements Cache {
  private final Map<Object, Object> storage;

  public SimpleCache() {
    this.storage = new ConcurrentHashMap<>();
  }

  @Override
  public synchronized void put(Object key, Object value) {
      storage.put(key, value);
  }

  @Override
  public Object get(Object key) {
    return storage.get(key);
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
  public int size() {
    return storage.size();
  }
  @Override
  public Object freeMemory(){
    return null;
  }

  @Override
  public boolean containsKey(Object key) {
    return storage.containsKey(key);
  }

  @Override
  public Set<Object> getKeys() {
    return storage.keySet();
  }
}