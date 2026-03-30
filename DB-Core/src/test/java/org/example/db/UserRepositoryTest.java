package org.example.db;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class UserRepositoryTest {

    private static UserRepository repository;

    @BeforeAll
    public static void setup() throws Exception {
        // Initialiser DatabaseManager avec H2
        DatabaseManager.init("jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        repository = new UserRepository();

        // Créer la table users
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "username VARCHAR(50) PRIMARY KEY," +
                    "password_hash VARCHAR(64) NOT NULL," +
                    "salt VARCHAR(32) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "active BOOLEAN DEFAULT TRUE" +
                    ")");

            // Mock de la procédure authenticate_user pour H2 en pointant vers la méthode statique ci-dessous
            st.execute("CREATE ALIAS IF NOT EXISTS authenticate_user FOR \"org.example.db.UserRepositoryTest.h2AuthenticateUser\"");
        }
    }

    /**
     * Méthode statique utilisée par H2 pour simuler la procédure stockée authenticate_user.
     */
    public static void h2AuthenticateUser(Connection conn, String user, String[] hash, String[] salt) throws SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT password_hash, salt FROM users WHERE username = ? AND active = TRUE")) {
            ps.setString(1, user);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    hash[0] = rs.getString(1);
                    salt[0] = rs.getString(2);
                }
            }
        }
    }

    @AfterAll
    public static void tearDown() {
        DatabaseManager.shutdown();
    }

    @BeforeEach
    public void clearTable() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM users");
        }
    }

    @Test
    public void testCreateAndExists() {
        assertTrue(repository.createUser("alice", "hash123", "salt456"));
        assertTrue(repository.exists("alice"));
        assertFalse(repository.exists("bob"));
    }

    @Test
    public void testGetAuthData() {
        repository.createUser("alice", "secret_hash", "random_salt");
        String[] data = repository.getAuthData("alice");
        assertNotNull(data);
        assertEquals("secret_hash", data[0]);
        assertEquals("random_salt", data[1]);
    }

    @Test
    public void testDeleteUser() {
        repository.createUser("alice", "h", "s");
        assertTrue(repository.exists("alice"));
        assertTrue(repository.deleteUser("alice"));
        assertFalse(repository.exists("alice"));
    }

    @Test
    public void testGetAllUsers() {
        repository.createUser("user1", "h", "s");
        repository.createUser("user2", "h", "s");
        List<String> users = repository.getAllUsers();
        assertEquals(2, users.size());
        assertTrue(users.contains("user1"));
        assertTrue(users.contains("user2"));
    }
}
