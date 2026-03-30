package org.example;

import org.example.client.AuthRestClient;
import org.example.db.DatabaseManager;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import java.io.*;
import java.net.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ImapIntegrationTest {

    private static ImapServer server;
    private static int port = 1430; // Port de test
    private static AuthRestClient mockAuthClient;

    @BeforeAll
    public static void startServer() throws Exception {
        // H2 configuration for IMAP tests
        DatabaseManager.init("jdbc:h2:mem:imap_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        
        // Create tables and aliases
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE emails (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "sender VARCHAR(100)," +
                    "recipient VARCHAR(100)," +
                    "subject VARCHAR(255)," +
                    "body TEXT," +
                    "sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "is_read BOOLEAN DEFAULT FALSE," +
                    "flags VARCHAR(255) DEFAULT ''," +
                    "is_deleted BOOLEAN DEFAULT FALSE" +
                    ")");

            st.execute("CREATE ALIAS fetch_emails AS ' public static java.sql.ResultSet fetchEmails(java.sql.Connection conn, String user) throws java.sql.SQLException { java.sql.PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM emails WHERE recipient = ? AND is_deleted = FALSE ORDER BY sent_at DESC\"); ps.setString(1, user); return ps.executeQuery(); } '");
            
            // Simuler un email en base
            st.execute("INSERT INTO emails (sender, recipient, subject, body) VALUES ('alice@test.com', 'bob@test.com', 'IMAP Subject', 'IMAP Body Content')");
        }

        mockAuthClient = mock(AuthRestClient.class);
        when(mockAuthClient.authenticate("bob", "password")).thenReturn("dummy-token");

        server = new ImapServer(port, null);
        server.setAuthClientOverride(mockAuthClient);
        
        new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {}
        }).start();
        
        TimeUnit.MILLISECONDS.sleep(500);
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) server.stop();
        DatabaseManager.shutdown();
    }

    @Test
    public void testImapSession() throws IOException {
        try (Socket socket = new Socket("localhost", port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)) {

            String line = in.readLine();
            assertTrue(line.contains("* OK"));

            out.println("A001 LOGIN bob password");
            line = in.readLine();
            assertTrue(line.contains("A001 OK"));

            out.println("A002 SELECT INBOX");
            // Le serveur IMAP envoie plusieurs lignes pour SELECT
            boolean existsSeen = false;
            while ((line = in.readLine()) != null) {
                if (line.contains("EXISTS")) existsSeen = true;
                if (line.contains("A002 OK")) break;
            }
            assertTrue(existsSeen);

            out.println("A003 FETCH 1 BODY[]");
            // Lire la réponse du FETCH (multiple lines)
            boolean bodySeen = false;
            while ((line = in.readLine()) != null) {
                if (line.contains("IMAP Body Content")) bodySeen = true;
                if (line.contains("A003 OK")) break;
            }
            assertTrue(bodySeen);

            out.println("A004 LOGOUT");
            line = in.readLine();
            assertTrue(line.contains("* BYE"));
        }
    }
}
