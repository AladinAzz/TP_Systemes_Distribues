package org.example.db;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class EmailRepositoryTest {

    private static EmailRepository repository;

    @BeforeAll
    public static void setup() throws Exception {
        // Initialiser DatabaseManager avec H2
        DatabaseManager.init("jdbc:h2:mem:testdb2;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        repository = new EmailRepository();

        // Créer la table emails
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS emails (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "sender VARCHAR(100) NOT NULL," +
                    "recipient VARCHAR(100) NOT NULL," +
                    "subject VARCHAR(255)," +
                    "body TEXT," +
                    "sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "is_read BOOLEAN DEFAULT FALSE," +
                    "flags VARCHAR(255) DEFAULT ''," +
                    "is_deleted BOOLEAN DEFAULT FALSE" +
                    ")");

            // Mock des procédures pour H2 en pointant vers les méthodes statiques ci-dessous
            st.execute("CREATE ALIAS IF NOT EXISTS store_email FOR \"org.example.db.EmailRepositoryTest.h2StoreEmail\"");
            st.execute("CREATE ALIAS IF NOT EXISTS fetch_emails FOR \"org.example.db.EmailRepositoryTest.h2FetchEmails\"");
            st.execute("CREATE ALIAS IF NOT EXISTS update_flags FOR \"org.example.db.EmailRepositoryTest.h2UpdateFlags\"");
        }
    }

    /**
     * Mock statique pour store_email.
     */
    public static int h2StoreEmail(Connection conn, String sender, String recipient, String subject, String body) throws SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement("INSERT INTO emails (sender, recipient, subject, body) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, sender);
            ps.setString(2, recipient);
            ps.setString(3, subject);
            ps.setString(4, body);
            return ps.executeUpdate();
        }
    }

    /**
     * Mock statique pour fetch_emails.
     */
    public static java.sql.ResultSet h2FetchEmails(Connection conn, String user) throws SQLException {
        java.sql.PreparedStatement ps = conn.prepareStatement("SELECT * FROM emails WHERE recipient = ? AND is_deleted = FALSE ORDER BY sent_at DESC");
        ps.setString(1, user);
        return ps.executeQuery();
    }

    public static int h2UpdateFlags(Connection conn, int id, String flags) throws SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement("UPDATE emails SET flags = ? WHERE id = ?")) {
            ps.setString(1, flags);
            ps.setInt(2, id);
            return ps.executeUpdate();
        }
    }

    @AfterAll
    public static void tearDown() {
        DatabaseManager.shutdown();
    }

    @Test
    public void testStoreAndFetch() {
        assertTrue(repository.storeEmail("alice@test.com", "bob@test.com", "Hello", "How are you?"));
        List<EmailRecord> emails = repository.fetchEmails("bob@test.com");
        assertEquals(1, emails.size());
        assertEquals("alice@test.com", emails.get(0).getSender());
        assertEquals("Hello", emails.get(0).getSubject());
        assertEquals("received", emails.get(0).getEmailType());
    }

    @Test
    public void testUpdateFlags() throws SQLException {
        repository.storeEmail("a", "b", "s", "b");
        List<EmailRecord> emails = repository.fetchEmails("b");
        int id = emails.get(0).getId();

        assertTrue(repository.updateFlags(id, "\\Seen \\Answered"));
        emails = repository.fetchEmails("b");
        assertTrue(emails.get(0).getFlags().contains("\\Seen"));
    }

    @Test
    public void testFetchSentEmails() {
        repository.storeEmail("sender@test.com", "receiver@test.com", "Sent Subject", "Sent Body");
        List<EmailRecord> sentEmails = repository.fetchSentEmails("sender");
        assertFalse(sentEmails.isEmpty());
        assertEquals("sender@test.com", sentEmails.get(0).getSender());
        assertEquals("sent", sentEmails.get(0).getEmailType());
    }
}
