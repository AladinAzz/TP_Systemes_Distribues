package org.example.client;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.util.Base64;
import java.util.Date;

/**
 * Shared JwtManager for token generation and validation.
 * The secret key is persisted to disk to maintain session validity across restarts.
 */
public class JwtManager {

    private static final String KEY_FILE_NAME = ".jwt_secret";
    private static final long EXPIRY_MS = 2 * 60 * 60 * 1000L; // 2 hours

    private final Key secretKey;

    public JwtManager() {
        this.secretKey = loadOrCreateKey();
    }

    private Key loadOrCreateKey() {
        File f = resolveKeyFile().toFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();

        if (f.exists()) {
            try {
                byte[] encoded = Files.readAllBytes(f.toPath());
                byte[] decoded = Base64.getDecoder().decode(encoded);
                return Keys.hmacShaKeyFor(decoded);
            } catch (Exception e) {
                System.err.println("[JwtManager] Could not read key file, generating new: " + e.getMessage());
            }
        }

        Key key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(Base64.getEncoder().encodeToString(key.getEncoded()));
        } catch (IOException e) {
            System.err.println("[JwtManager] WARNING: Could not persist key: " + e.getMessage());
        }
        return key;
    }

    private Path resolveKeyFile() {
        String explicit = System.getProperty("jwt.key.file");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("JWT_KEY_FILE");
        }
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path repoRootCandidate = null;
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path pom = p.resolve("pom.xml");
            Path mailserverDir = p.resolve("mailserver");
            if (Files.exists(pom) && Files.isDirectory(mailserverDir)) {
                // Keep going upward and use the highest matching project root.
                repoRootCandidate = p;
            }
        }

        if (repoRootCandidate != null) {
            return repoRootCandidate.resolve("mailserver").resolve(KEY_FILE_NAME);
        }

        return cwd.resolve("mailserver").resolve(KEY_FILE_NAME);
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRY_MS);
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public String validateTokenAndGetUsername(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
