package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Collections;

@SpringBootApplication
public class SmtpApplication {

    public static void main(String[] args) {
        // FIX 7 — Explicitly set port 8080 (same pattern as POP3/IMAP modules)
        // so the port is documented and consistent, not left to Spring's default.
        SpringApplication app = new SpringApplication(SmtpApplication.class);
        app.setDefaultProperties(Collections.singletonMap("server.port", "8080"));
        app.run(args);
    }
}