package com.mipt.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class LRUCache implements Cache {
  private final int capacity;
  private final Map<Object, Node> storage;
  private final DoublyLinkedList accessOrder;

  public LRUCache(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Capacity must be positive");
    }
    this.capacity = capacity;
    this.storage = new ConcurrentHashMap<>();
    this.accessOrder = new DoublyLinkedList();
  }

  @Override
  public synchronized void put(Object key, Object value) {
    if (key == null || value == null) {
      throw new IllegalArgumentException("Key and value cannot be null");
    }

    if (storage.containsKey(key)) {
      Node node = storage.get(key);
      node.value = value;
      accessOrder.moveToHead(node);
    } else {
      if (storage.size() >= capacity) {
        Node tail = accessOrder.removeTail();
        storage.remove(tail.key);
      }

      Node newNode = new Node(key, value);
      storage.put(key, newNode);
      accessOrder.addToHead(newNode);
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
      accessOrder.removeNode(node);
    }
  }

  @Override
  public synchronized void clear() {
    storage.clear();
    accessOrder.clear();
  }

  @Override
  public synchronized int size() {
    return storage.size();
  }

  @Override
  public synchronized boolean containsKey(Object key) {
    return storage.containsKey(key);
  }

  public int getCapacity() {
    return capacity;
  }

  // Вспомогательные классы
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
