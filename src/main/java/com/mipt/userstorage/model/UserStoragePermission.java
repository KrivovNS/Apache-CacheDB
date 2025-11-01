package com.mipt.userstorage.model;

public class UserStoragePermission {

  private Long userId;
  private Long storageId;
  private String permissionType;

  public UserStoragePermission() {
  }

  public UserStoragePermission(Long userId, Long storageId, String permissionType) {
    this.userId = userId;
    this.storageId = storageId;
    this.permissionType = permissionType;
  }

  // Геттеры и сеттеры
  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Long getStorageId() {
    return storageId;
  }

  public void setStorageId(Long storageId) {
    this.storageId = storageId;
  }

  public String getPermissionType() {
    return permissionType;
  }

  public void setPermissionType(String permissionType) {
    this.permissionType = permissionType;
  }
}