package org.example.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * FIX 1 — Passwords are now hashed with SHA-256 + a per-user salt.
 * Plain-text comparison is removed entirely.
 * (Use BCrypt in production; SHA-256+salt requires no extra dependency.)
 *
 * FIX 12 — loadUsers() is no longer called on every operation.
 * The in-memory map is the source of truth; disk is only read once
 * at startup and written only when data changes.
 */
public class UserManager {

    private static final String FILE_PATH = "mailserver/users.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, User> users = new HashMap<>();

    public UserManager() {
        loadUsers();
    }

    // ── Disk I/O ──────────────────────────────────────────────────────────────

    private synchronized void loadUsers() {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            users = new HashMap<>();
            saveUsers();
            return;
        }
        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, User>>() {
            }.getType();
            Map<String, User> loaded = gson.fromJson(reader, type);
            users = (loaded != null) ? loaded : new HashMap<>();
        } catch (IOException e) {
            System.err.println("Error reading " + FILE_PATH + ": " + e.getMessage());
            users = new HashMap<>();
        }
    }

    private synchronized void saveUsers() {
        File file = new File(FILE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(users, writer);
        } catch (IOException e) {
            System.err.println("Error writing " + FILE_PATH + ": " + e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * FIX 1: compare hashed password, not plain text.
     */
    public synchronized boolean authenticate(String username, String password) {
        User user = users.get(username);
        if (user == null)
            return false;
        String hashed = hashPassword(password, user.getSalt());
        return hashed.equals(user.getPasswordHash());
    }

    public synchronized boolean exists(String username) {
        return users.containsKey(username);
    }

    /**
     * FIX 1: store hash + salt instead of plain-text password.
     */
    public synchronized boolean createUser(String username, String password) {
        if (users.containsKey(username))
            return false;
        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        users.put(username, new User(username, hash, salt));
        saveUsers();
        return true;
    }

    public synchronized boolean updateUser(String username, String newPassword) {
        if (!users.containsKey(username))
            return false;
        String salt = generateSalt();
        String hash = hashPassword(newPassword, salt);
        users.put(username, new User(username, hash, salt));
        saveUsers();
        return true;
    }

    public synchronized boolean deleteUser(String username) {
        if (users.remove(username) != null) {
            saveUsers();
            return true;
        }
        return false;
    }

    public synchronized List<String> getAllUsers() {
        return new ArrayList<>(users.keySet());
    }

    // ── Hashing helpers ───────────────────────────────────────────────────────

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

    // ── Inner model ───────────────────────────────────────────────────────────

    public static class User {
        private String username;
        private String passwordHash; // FIX 1: was "password" (plain text)
        private String salt; // FIX 1: new field

        public User() {
        }

        public User(String username, String passwordHash, String salt) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.salt = salt;
        }

        public String getUsername() {
            return username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public String getSalt() {
            return salt;
        }

        // Legacy getter kept so existing Gson-deserialised entries don't NPE.
        // Remove after migrating users.json.
        public String getPassword() {
            return passwordHash;
        }
    }
}