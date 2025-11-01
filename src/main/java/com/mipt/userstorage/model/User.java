package com.mipt.userstorage.model;

public class User {

  private Long id;
  private String username;
  private String passwordPlain;

  public User() {
  }

  public User(String username, String passwordPlain) {
    this.username = username;
    this.passwordPlain = passwordPlain;
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

  public String getPasswordPlain() {
    return passwordPlain;
  }

  public void setPasswordPlain(String passwordPlain) {
    this.passwordPlain = passwordPlain;
  }
}