package com.mipt;

import com.mipt.controller.NettyTcpHandler;
import com.mipt.database.dao.UserDAO;
import com.mipt.database.initialization.DatabaseConnection;
import com.mipt.database.initialization.DatabaseInitializer;
import com.mipt.service.CacheStorageService;
import com.mipt.service.SessionService;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpAuthTest {

    private CacheStorageService cacheService;
    private SessionService sessionService;
    private UserDAO userDAO;
    private EmbeddedChannel channel;

    @BeforeAll
    void setupTestDatabase() throws Exception {
        System.out.println("=== Setting up test database ===");

        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            fail("H2 driver not found");
        }

        System.setProperty("db.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        DatabaseInitializer.initializeDatabase();

        // Добавляем тестового пользователя
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement()) {

            // СНАЧАЛА удаляем старого пользователя, если он есть
            stmt.execute("DELETE FROM users WHERE username = 'testuser'");

            // ПОТОМ создаем нового
            stmt.execute("INSERT INTO users (username, password, permission) VALUES " +
                    "('testuser', 'testpass', 'admin')");

            System.out.println("Test user created");
        }
    }

    @BeforeEach
    void setUp() {
        cacheService = new CacheStorageService();
        sessionService = new SessionService();
        userDAO = new UserDAO();

        NettyTcpHandler handler = new NettyTcpHandler(cacheService, sessionService, userDAO);
        channel = new EmbeddedChannel(handler);
    }

    @AfterEach
    void tearDown() {
        channel.close();
    }

    @AfterAll
    void cleanupDatabase() {
        DatabaseConnection.closeConnection();
    }

    private String readResponse() {
        String response = channel.readOutbound();
        // Убираем перенос строки для сравнения
        return response != null ? response.replace("\n", "") : null;
    }

    @Test
    void testAuthSuccess() {
        System.out.println("Testing successful authentication...");

        channel.writeInbound("AUTH testuser testpass");
        String response = readResponse();

        assertNotNull(response);
        assertTrue(response.startsWith("OK session_token="));
        System.out.println("Response: " + response);
        System.out.println("✓ Authentication successful");
    }

    @Test
    void testAuthWrongPassword() {
        System.out.println("Testing wrong password...");

        channel.writeInbound("AUTH testuser wrongpass");
        String response = readResponse();

        assertEquals("ERROR Invalid login or password", response);
        System.out.println("Response: " + response);
        System.out.println("✓ Wrong password handled correctly");
    }

    @Test
    void testAuthNonexistentUser() {
        System.out.println("Testing nonexistent user...");

        channel.writeInbound("AUTH nonexistent anypass");
        String response = readResponse();

        assertEquals("ERROR Invalid login or password", response);
        System.out.println("Response: " + response);
        System.out.println("✓ Nonexistent user handled correctly");
    }

    @Test
    void testAuthMissingParameters() {
        System.out.println("Testing missing parameters...");

        channel.writeInbound("AUTH testuser");
        String response = readResponse();

        assertEquals("ERROR Usage: AUTH <login> <password>", response);
        System.out.println("Response: " + response);
        System.out.println("✓ Missing parameters handled correctly");
    }

    @Test
    void testAuthCaseInsensitive() {
        System.out.println("Testing case insensitive command...");

        channel.writeInbound("auth testuser testpass");
        String response = readResponse();

        assertNotNull(response);
        assertTrue(response.startsWith("OK session_token="));
        System.out.println("Response: " + response);
        System.out.println("✓ Case insensitive command works");
    }

    @Test
    void testAuthWithExtraSpaces() {
        System.out.println("Testing auth with extra spaces...");

        channel.writeInbound("AUTH   testuser   testpass  ");
        String response = readResponse();

        assertNotNull(response);
        // Проверяем что начинается с OK (не ошибка)
        assertTrue(response.startsWith("OK"), "Response should start with OK, but was: " + response);
        System.out.println("Response: " + response);
        System.out.println("✓ Extra spaces handled correctly");
    }
}