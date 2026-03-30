package org.example.server;

import org.example.db.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Contrôleur REST pour l'authentification et la gestion des utilisateurs.
 * Remplace l'ancienne implémentation RMI.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtManager jwtManager;

    public AuthController(UserRepository userRepository, JwtManager jwtManager) {
        this.userRepository = userRepository;
        this.jwtManager = jwtManager;
    }

    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticate(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password required"));
        }

        String[] authData = userRepository.getAuthData(username);
        if (authData != null) {
            String dbHash = authData[0];
            String dbSalt = authData[1];
            String inputHash = hashPassword(password, dbSalt);

            if (inputHash.equals(dbHash)) {
                String token = jwtManager.generateToken(username);
                return ResponseEntity.ok(Map.of("token", token));
            }
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
    }

    @GetMapping("/users/{username}/exists")
    public ResponseEntity<?> userExists(@PathVariable("username") String username) {
        boolean exists = userRepository.exists(username);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password required"));
        }

        if (userRepository.exists(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "User already exists"));
        }

        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        if (userRepository.createUser(username, hash, salt)) {
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }
        return ResponseEntity.internalServerError().build();
    }

    @PutMapping("/users/{username}/password")
    public ResponseEntity<?> updatePassword(@PathVariable("username") String username, @RequestBody Map<String, String> request) {
        String newPassword = request.get("newPassword");
        if (newPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "newPassword required"));
        }

        if (!userRepository.exists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String salt = generateSalt();
        String hash = hashPassword(newPassword, salt);
        if (userRepository.updatePassword(username, hash, salt)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.internalServerError().build();
    }

    @DeleteMapping("/users/{username}")
    public ResponseEntity<?> deleteUser(@PathVariable("username") String username) {
        if (userRepository.deleteUser(username)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping("/users")
    public ResponseEntity<List<String>> getAllUsers() {
        return ResponseEntity.ok(userRepository.getAllUsers());
    }

    // ── Hashing Helpers (Duplicated from UserManager for now) ──────────────────

    private static String generateSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(salt));
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
