package org.example.server;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Key;
import java.util.Base64;
import java.util.Date;

/**
 * FIX 2 — The secret key is now persisted to disk on first launch and reloaded
 * on subsequent launches. Tokens issued before a restart remain valid
 * as long as they have not expired.
 *
 * Key file location: mailserver/.jwt_secret
 * (keep this file out of version control — add it to .gitignore)
 */
public class JwtManager {

    private static final String KEY_FILE = "mailserver/.jwt_secret";
    private static final long EXPIRY_MS = 2 * 60 * 60 * 1000L; // 2 hours

    private final Key secretKey;

    public JwtManager() {
        this.secretKey = loadOrCreateKey();
    }

    // ── Key persistence ───────────────────────────────────────────────────────

    private Key loadOrCreateKey() {
        File f = new File(KEY_FILE);

        // Ensure parent directory exists
        File parent = f.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();

        if (f.exists()) {
            try {
                byte[] encoded = Files.readAllBytes(Paths.get(KEY_FILE));
                byte[] decoded = Base64.getDecoder().decode(encoded);
                return Keys.hmacShaKeyFor(decoded);
            } catch (Exception e) {
                System.err.println("[JwtManager] Could not read key file, generating a new one: " + e.getMessage());
            }
        }

        // Generate fresh key and persist it
        Key key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(Base64.getEncoder().encodeToString(key.getEncoded()));
            System.out.println("[JwtManager] New JWT secret key generated and saved to " + KEY_FILE);
        } catch (IOException e) {
            System.err.println(
                    "[JwtManager] WARNING: Could not persist key — tokens will be lost on restart: " + e.getMessage());
        }
        return key;
    }

    // ── Public API ────────────────────────────────────────────────────────────

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

    /**
     * Validates the token and returns the username, or null if invalid/expired.
     */
    public String validateTokenAndGetUsername(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (Exception e) {
            System.err.println("[JwtManager] Invalid token: " + e.getMessage());
            return null;
        }
    }
}