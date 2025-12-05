package com.mipt.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleCache implements Cache {
  private final int maxCapacityBytes;
  private final Map<Object, Object> storage;
  private final AtomicLong currentSizeBytes;
  private final Map<Object, Long> entrySizes;

  public SimpleCache(int maxCapacityBytes) {
    this.maxCapacityBytes = maxCapacityBytes;
    this.storage = new ConcurrentHashMap<>();
    this.currentSizeBytes = new AtomicLong(0);
    this.entrySizes = new ConcurrentHashMap<>();
  }

  @Override
  public synchronized void put(Object key, Object value, long size) {
    if (size > maxCapacityBytes) {
      throw new IllegalArgumentException("Entry size exceeds cache capacity");
    }

    if (storage.containsKey(key)) {
      long oldSize = entrySizes.getOrDefault(key, 0L);
      storage.put(key, value);
      entrySizes.put(key, size);
      currentSizeBytes.addAndGet(size - oldSize);
    } else {
      if (currentSizeBytes.get() + size > maxCapacityBytes) {
        throw new IllegalStateException("Cache overflow");
      }
      storage.put(key, value);
      entrySizes.put(key, size);
      currentSizeBytes.addAndGet(size);
    }
  }

  @Override
  public Object get(Object key) {
    return storage.get(key);
  }

  @Override
  public synchronized void remove(Object key) {
    Long size = entrySizes.remove(key);
    if (size != null) {
      currentSizeBytes.addAndGet(-size);
    }
    storage.remove(key);
  }

  @Override
  public synchronized void clear() {
    storage.clear();
    entrySizes.clear();
    currentSizeBytes.set(0);
  }

  @Override
  public int size() {
    return storage.size();
  }

  @Override
  public long totalSizeInBytes() {
    return currentSizeBytes.get();
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