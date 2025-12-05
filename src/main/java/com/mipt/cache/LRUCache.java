package com.mipt.cache;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class LRUCache implements Cache {
  private final int maxCapacityBytes;
  private final Map<Object, Node> storage;
  private final DoublyLinkedList accessOrder;
  private final AtomicLong currentSizeBytes;
  private final Map<Object, Long> entrySizes;

  public LRUCache(int maxCapacityBytes) {
    if (maxCapacityBytes <= 0) {
      throw new IllegalArgumentException("Capacity must be positive");
    }
    this.maxCapacityBytes = maxCapacityBytes;
    this.storage = new ConcurrentHashMap<>();
    this.accessOrder = new DoublyLinkedList();
    this.currentSizeBytes = new AtomicLong(0);
    this.entrySizes = new ConcurrentHashMap<>();
  }

  @Override
  public synchronized void put(Object key, Object value, long size) {
    if (key == null || value == null) {
      throw new IllegalArgumentException("Key and value cannot be null");
    }

    if (size <= 0) {
      throw new IllegalArgumentException("Size must be positive");
    }

    // Проверяем, поместится ли элемент
    if (size > maxCapacityBytes) {
      throw new IllegalArgumentException("Entry size exceeds cache capacity");
    }

    if (storage.containsKey(key)) {
      // Обновляем существующий
      Node node = storage.get(key);
      long oldSize = entrySizes.get(key);
      node.value = value;
      accessOrder.moveToHead(node);

      // Обновляем размер
      entrySizes.put(key, size);
      currentSizeBytes.addAndGet(size - oldSize);

      // Проверяем переполнение
      checkAndHandleOverflow();
    } else {
      // Добавляем новый
      if (currentSizeBytes.get() + size > maxCapacityBytes) {
        // Удаляем элементы, пока не освободится место
        evictEntriesUntilSpaceAvailable(size);
      }

      Node newNode = new Node(key, value);
      storage.put(key, newNode);
      accessOrder.addToHead(newNode);
      entrySizes.put(key, size);
      currentSizeBytes.addAndGet(size);
    }
  }

  @Override
  public synchronized Object get(Object key) {
    if (key == null) return null;

    Node node = storage.get(key);
    if (node == null) {
      return null;
    }

    accessOrder.moveToHead(node);
    return node.value;
  }

  @Override
  public synchronized void remove(Object key) {
    if (key == null) return;

    Node node = storage.remove(key);
    if (node != null) {
      Long size = entrySizes.remove(key);
      if (size != null) {
        currentSizeBytes.addAndGet(-size);
      }
      accessOrder.removeNode(node);
    }
  }

  private void checkAndHandleOverflow() {
    if (currentSizeBytes.get() > maxCapacityBytes) {
      evictEntriesUntilSpaceAvailable(0);
    }
  }

  private void evictEntriesUntilSpaceAvailable(long requiredSpace) {
    while (!storage.isEmpty() &&
        (currentSizeBytes.get() + requiredSpace) > maxCapacityBytes) {
      Node tail = accessOrder.removeTail();
      if (tail != null) {
        Long size = entrySizes.remove(tail.key);
        if (size != null) {
          currentSizeBytes.addAndGet(-size);
        }
        storage.remove(tail.key);
      }
    }
  }

  @Override
  public synchronized void clear() {
    storage.clear();
    accessOrder.clear();
    entrySizes.clear();
    currentSizeBytes.set(0);
  }

  @Override
  public synchronized int size() {
    return storage.size();
  }

  @Override
  public synchronized long totalSizeInBytes() {
    return currentSizeBytes.get();
  }

  @Override
  public synchronized boolean containsKey(Object key) {
    return storage.containsKey(key);
  }

  @Override
  public Set<Object> getKeys() {
    return storage.keySet();
  }

  public int getMaxCapacityBytes() {
    return maxCapacityBytes;
  }

  // Вспомогательные классы остаются без изменений
  private static class Node {
    Object key;
    Object value;
    Node prev;
    Node next;

    Node(Object key, Object value) {
      this.key = key;
      this.value = value;
    }
  }

  private static class DoublyLinkedList {
    private Node head;
    private Node tail;

    void addToHead(Node node) {
      if (head == null) {
        head = tail = node;
      } else {
        node.next = head;
        head.prev = node;
        head = node;
      }
    }

    void removeNode(Node node) {
      if (node.prev != null) {
        node.prev.next = node.next;
      } else {
        head = node.next;
      }

      if (node.next != null) {
        node.next.prev = node.prev;
      } else {
        tail = node.prev;
      }

      node.prev = null;
      node.next = null;
    }

    void moveToHead(Node node) {
      removeNode(node);
      addToHead(node);
    }

    Node removeTail() {
      if (tail == null) return null;

      Node oldTail = tail;
      removeNode(oldTail);
      return oldTail;
    }

    void clear() {
      head = tail = null;
    }
  }
}