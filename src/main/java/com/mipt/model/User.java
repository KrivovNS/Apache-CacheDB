package com.mipt.model;

public class User {
  private Long id;
  private String username;
  private String password;
  private String storageToken; // Токен хранилища пользователя

  public User() {}

  public User(Long id, String username, String password, String storageToken) {
    this.id = id;
    this.username = username;
    this.password = password;
    this.storageToken = storageToken;
  }

  // Геттеры и сеттеры
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getStorageToken() {
    return storageToken;
  }

  public void setStorageToken(String storageToken) {
    this.storageToken = storageToken;
  }
}