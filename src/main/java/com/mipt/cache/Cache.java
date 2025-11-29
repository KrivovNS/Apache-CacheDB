package com.mipt.cache;

import java.util.Set;

public interface Cache {
  void put(Object key, Object value);
  Object get(Object key);
  void remove(Object key);
  void clear();
  int size();
  boolean containsKey(Object key);
  Set<Object> getKeys();
}
