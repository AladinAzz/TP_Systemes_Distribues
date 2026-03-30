package org.example;

import org.example.client.AuthRestClient;
import org.example.db.DatabaseManager;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class Pop3IntegrationTest {

    private static Pop3Server server;
    private static int port = 1100; // Port de test
    private static AuthRestClient mockAuthClient;

    @BeforeAll
    public static void startServer() throws Exception {
        // H2 configuration for POP3 tests
        DatabaseManager.init("jdbc:h2:mem:pop3_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        
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

            st.execute("CREATE ALIAS fetch_emails FOR \"org.example.Pop3IntegrationTest.h2FetchEmails\"");
            
            // Simuler quelques emails en base
            st.execute("INSERT INTO emails (sender, recipient, subject, body) VALUES ('alice@test.com', 'bob', 'Hello', 'First message content')");
        }

        mockAuthClient = new AuthRestClient() {
            @Override
            public String authenticate(String username, String password) {
                if ("bob".equals(username) && "password".equals(password)) {
                    return "dummy-token";
                }
                return null;
            }
        };

        server = new Pop3Server(port, null);
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
    public void testPop3Retrieval() throws IOException {
        try (Socket socket = new Socket("localhost", port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String line = in.readLine();
            assertTrue(line.startsWith("+OK"));

            out.println("USER bob");
            line = in.readLine();
            assertTrue(line.startsWith("+OK"));

            out.println("PASS password");
            line = in.readLine();
            assertTrue(line.startsWith("+OK"));

            out.println("STAT");
            line = in.readLine();
            assertTrue(line.startsWith("+OK 1")); // 1 message

            out.println("RETR 1");
            line = in.readLine();
            assertTrue(line.startsWith("+OK"));
            
            // Lire le contenu du message et le point final
            line = in.readLine();
            assertEquals("First message content", line);
            line = in.readLine();
            assertEquals(".", line);

            out.println("QUIT");
        }
    }

    public static java.sql.ResultSet h2FetchEmails(Connection conn, String user) throws Exception {
        java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM emails WHERE recipient = ? AND is_deleted = FALSE ORDER BY sent_at DESC");
        ps.setString(1, user);
        return ps.executeQuery();
    }
}
