package com.mipt;

import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.database.initialization.DatabaseInitializer;
import org.junit.jupiter.api.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseTest {

  @BeforeAll
  void setupTestDatabase() throws Exception {
    System.out.println("=== Setting up test database ===");

    // Используем H2 in-memory базу для тестов
    try {
      Class.forName("org.h2.Driver");
    } catch (ClassNotFoundException e) {
      fail("H2 driver not found. Add dependency to pom.xml");
    }

    // Инициализируем базу
    System.setProperty("db.url", "jdbc:h2:mem:databasetest;DB_CLOSE_DELAY=-1");
    DatabaseInitializer.initializeDatabase();

    System.out.println("Test database initialized");
  }

  @Test
  void testDatabaseConnection() throws Exception {
    System.out.println("Testing database connection...");

    try (Connection conn = DatabaseConnection.getConnection()) {
      assertNotNull(conn);
      assertFalse(conn.isClosed());
      assertTrue(conn.isValid(2));

      System.out.println("Database connection successful");
    }
  }

  @Test
  void testDefaultUserExists() throws Exception {
    System.out.println("Testing default user...");

    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT * FROM users WHERE username = 'default'")) {

      assertTrue(rs.next());
      assertEquals("default", rs.getString("username"));
      assertEquals("admin123", rs.getString("password"));
      assertEquals("superadmin", rs.getString("permission"));

      System.out.println("Default user verified");
    }
  }

  @Test
  void testUserTableStructure() throws Exception {
    System.out.println("Testing user table structure...");

    try (Connection conn = DatabaseConnection.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) as count FROM users")) {

      assertTrue(rs.next());
      int count = rs.getInt("count");
      assertTrue(count >= 1);

      System.out.println("User table contains " + count + " records");
    }
  }

  @AfterAll
  static void cleanupDatabase() {
    System.out.println("=== Cleaning up test database ===");
    DatabaseConnection.closeConnection();
    System.clearProperty("db.url");
  }
}
