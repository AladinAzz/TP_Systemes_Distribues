package org.example.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class UserManager {
    // Chemin relatif vers le fichier JSON utilisé par le projet actuel
    // Ce chemin assume que le serveur RMI est lancé depuis la racine du projet
    private static final String FILE_PATH = "mailserver/users.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    // Cache en mémoire pour éviter la lecture constante du disque
    private Map<String, User> users = new HashMap<>();

    public UserManager() {
        loadUsers();
    }

    private synchronized void loadUsers() {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            users = new HashMap<>();
            saveUsers(); // Créé le fichier vide
            return;
        }

        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, User>>(){}.getType();
            Map<String, User> loadedUsers = gson.fromJson(reader, type);
            if (loadedUsers != null) {
                users = loadedUsers;
            } else {
                users = new HashMap<>();
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la lecture de " + FILE_PATH + " : " + e.getMessage());
            users = new HashMap<>();
        }
    }

    private synchronized void saveUsers() {
        File file = new File(FILE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs(); // Assure que le dossier mailserver existe
        }
        
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(users, writer);
        } catch (IOException e) {
            System.err.println("Erreur lors de l'écriture dans " + FILE_PATH + " : " + e.getMessage());
        }
    }

    public synchronized boolean authenticate(String username, String password) {
        loadUsers(); // Recharger au cas où un autre processus a modifié
        User user = users.get(username);
        return user != null && user.getPassword().equals(password);
    }

    public synchronized boolean exists(String username) {
        loadUsers();
        return users.containsKey(username);
    }

    public synchronized boolean createUser(String username, String password) {
        loadUsers();
        if (users.containsKey(username)) {
            return false;
        }
        users.put(username, new User(username, password));
        saveUsers();
        return true;
    }

    public synchronized boolean updateUser(String username, String newPassword) {
        loadUsers();
        if (!users.containsKey(username)) {
            return false;
        }
        users.put(username, new User(username, newPassword));
        saveUsers();
        return true;
    }

    public synchronized boolean deleteUser(String username) {
        loadUsers();
        if (users.remove(username) != null) {
            saveUsers();
            return true;
        }
        return false;
    }

    public synchronized List<String> getAllUsers() {
        loadUsers();
        return new ArrayList<>(users.keySet());
    }
    
    // Classe interne pour la représentation JSON
    public static class User {
        private String username;
        private String password;

        public User() {} // Requis pour Gson
        public User(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() { return username; }
        public String getPassword() { return password; }
    }
}
