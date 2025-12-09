package com.mipt.cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LFUCache implements Cache {

  private final Map<Object, CacheNode> storage;
  private final Map<Integer, LinkedHashSet<Object>> frequencyList;
  private int minFrequency;

  public LFUCache() {
    this.storage = new ConcurrentHashMap<>();
    this.frequencyList = new HashMap<>();
    this.minFrequency = 0;
  }

  @Override
  public synchronized void put(Object key, Object value) {
    if (storage.containsKey(key)) {
      CacheNode node = storage.get(key);
      node.value = value;
      incrementFrequency(key, node);
    } else {
      CacheNode newNode = new CacheNode(value, 1);
      storage.put(key, newNode);
      frequencyList.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
      minFrequency = 1;
    }
  }

  @Override
  public synchronized Object get(Object key) {
    CacheNode node = storage.get(key);
    if (node == null) return null;
    incrementFrequency(key, node);
    return node.value;
  }

  @Override
  public synchronized void remove(Object key) {
    CacheNode node = storage.remove(key);
    if (node != null) {
      int freq = node.frequency;
      frequencyList.get(freq).remove(key);
      if (frequencyList.get(freq).isEmpty() && freq == minFrequency) {
        minFrequency++;
      }
    }
  }

  @Override
  public Object freeMemory() {
    if (!frequencyList.containsKey(minFrequency)) return null;
    Iterator<Object> it = frequencyList.get(minFrequency).iterator();
    if (it.hasNext()) {
      Object key = it.next();
      remove(key);
      return key;
    }
    return null;
  }

  @Override
  public synchronized void clear() {
    storage.clear();
    frequencyList.clear();
    minFrequency = 0;
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

  private void incrementFrequency(Object key, CacheNode node) {
    int freq = node.frequency;
    frequencyList.get(freq).remove(key);
    if (frequencyList.get(freq).isEmpty() && freq == minFrequency) {
      minFrequency++;
    }
    node.frequency++;
    frequencyList.computeIfAbsent(node.frequency, k -> new LinkedHashSet<>()).add(key);
  }

  private static class CacheNode {
    Object value;
    int frequency;

    CacheNode(Object value, int frequency) {
      this.value = value;
      this.frequency = frequency;
    }
  }
}
