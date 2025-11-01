package com.mipt.userstorage.dao;

import com.mipt.userstorage.database.DatabaseConnection;
import com.mipt.userstorage.model.User;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserDAO {

  public List<User> findAll() {
    List<User> users = new ArrayList<>();
    String sql = "SELECT * FROM users";

    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {

      while (rs.next()) {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordPlain(rs.getString("password_plain"));
        users.add(user);
      }

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return users;
  }

  public User findByUsername(String username) {
    String sql = "SELECT * FROM users WHERE username = ?";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, username);
      ResultSet rs = stmt.executeQuery();

      if (rs.next()) {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordPlain(rs.getString("password_plain"));
        return user;
      }

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return null;
  }

  public boolean createUser(String username, String passwordPlain) {
    String sql = "INSERT INTO users (username, password_plain) VALUES (?, ?)";

    try (Connection conn = DatabaseConnection.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

      stmt.setString(1, username);
      stmt.setString(2, passwordPlain);
      return stmt.executeUpdate() > 0;

    } catch (SQLException e) {
      e.printStackTrace();
      return false;
    }
  }
}