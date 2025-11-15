package com.mipt.userstorage.model;

public class CacheStorageEntity {

  private Long id;
  private String storageName;
  private String cacheType;
  private Integer maxSize;
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

  public String getStorageName() {
    return storageName;
  }

  public void setStorageName(String storageName) {
    this.storageName = storageName;
  }

  public String getCacheType() {
    return cacheType;
  }

  public void setCacheType(String cacheType) {
    this.cacheType = cacheType;
  }

  public Integer getMaxSize() {
    return maxSize;
  }

  public void setMaxSize(Integer maxSize) {
    this.maxSize = maxSize;
  }


  public String getStorageToken() {
    return storageToken;
  }

  public void setStorageToken(String storageToken) {
    this.storageToken = storageToken;
  }
}
