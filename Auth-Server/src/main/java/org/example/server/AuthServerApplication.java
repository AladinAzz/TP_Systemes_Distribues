package org.example.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée de l'application Auth-Server Spring Boot.
 */
@SpringBootApplication
public class AuthServerApplication {
    public static void main(String[] args) {
        // Désactivation de RMI car remplacé par REST
        SpringApplication.run(AuthServerApplication.class, args);
        System.out.println("✅ Auth-Server REST démarré sur le port 8090.");
    }
}
