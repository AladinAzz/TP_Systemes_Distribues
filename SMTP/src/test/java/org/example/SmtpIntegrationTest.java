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

public class SmtpIntegrationTest {

    private static SmtpServer server;
    private static int port = 2525; // Port de test
    private static AuthRestClient mockAuthClient;

    @BeforeAll
    public static void startServer() throws Exception {
        // H2 configuration for SMTP tests
        DatabaseManager.init("jdbc:h2:mem:smtp_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        
        // Create emails table
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
            st.execute("CREATE ALIAS store_email FOR \"org.example.SmtpIntegrationTest.h2StoreEmail\"");
        }

        mockAuthClient = new AuthRestClient() {
            @Override
            public boolean userExists(String username) {
                return "alice".equals(username);
            }
        };

        server = new SmtpServer(port, null);
        server.setAuthClientOverride(mockAuthClient);
        new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {}
        }).start();
        
        // Wait for server to start
        TimeUnit.MILLISECONDS.sleep(500);
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) server.stop();
        DatabaseManager.shutdown();
    }

    @Test
    public void testSmtpSession() throws IOException {
        try (Socket socket = new Socket("localhost", port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            // Greeting
            String line = in.readLine();
            assertTrue(line.startsWith("220"));

            out.println("HELO localhost");
            line = in.readLine();
            assertTrue(line.startsWith("250"));

            out.println("MAIL FROM:<alice@test.com>");
            line = in.readLine();
            assertTrue(line.startsWith("250"));

            out.println("RCPT TO:<bob@test.com>");
            line = in.readLine();
            assertTrue(line.startsWith("250"));

            out.println("DATA");
            line = in.readLine();
            assertTrue(line.startsWith("354"));

            out.println("Subject: Test SMTP");
            out.println("");
            out.println("Hello Bob!");
            out.println(".");
            line = in.readLine();
            assertTrue(line.startsWith("250"));

            out.println("QUIT");
        }
    }

    public static int h2StoreEmail(Connection conn, String sender, String recipient, String subject, String body) throws Exception {
        try (var ps = conn.prepareStatement("INSERT INTO emails (sender, recipient, subject, body) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, sender);
            ps.setString(2, recipient);
            ps.setString(3, subject);
            ps.setString(4, body);
            return ps.executeUpdate();
        }
    }
}
