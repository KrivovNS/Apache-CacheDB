package com.mipt.userstorage.model;

public class CacheStorageEntity {

  private Long id;
  private String storageToken;

  public CacheStorageEntity() {
  }

  public CacheStorageEntity(String storageToken) {
    this.storageToken = storageToken;
  }

  // Геттеры и сеттеры
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getStorageToken() {
    return storageToken;
  }

  public void setStorageToken(String storageToken) {
    this.storageToken = storageToken;
  }
}