package org.example.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client HTTP pour communiquer avec Auth-Server REST.
 * Remplace l'ancienne interface RMI IAuthService.
 */
public class AuthRestClient {

    private static final String BASE_URL = "http://localhost:8090/auth";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AuthRestClient() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Tente d'authentifier un utilisateur.
     * @return Le token JWT si succès, null sinon.
     */
    public String authenticate(String username, String password) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "username", username,
                    "password", password
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/authenticate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                return node.get("token").asText();
            }
        } catch (Exception e) {
            System.err.println("[AuthRestClient] Erreur authentification: " + e.getMessage());
        }
        return null;
    }

    /**
     * Vérifie si un utilisateur existe.
     */
    public boolean userExists(String username) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/users/" + username + "/exists"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                return node.get("exists").asBoolean();
            }
        } catch (Exception e) {
            System.err.println("[AuthRestClient] Erreur userExists: " + e.getMessage());
        }
        return false;
    }

    /**
     * Crée un utilisateur.
     */
    public boolean createUser(String username, String password) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "username", username,
                    "password", password
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/users"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 201;
        } catch (Exception e) {
            System.err.println("[AuthRestClient] Erreur createUser: " + e.getMessage());
        }
        return false;
    }

    /**
     * Supprime un utilisateur.
     */
    public boolean deleteUser(String username) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/users/" + username))
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[AuthRestClient] Erreur deleteUser: " + e.getMessage());
        }
        return false;
    }

    /**
     * Récupère la liste de tous les noms d'utilisateurs.
     */
    public List<String> getAllUsers() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/users"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                List<String> users = new ArrayList<>();
                node.forEach(n -> users.add(n.asText()));
                return users;
            }
        } catch (Exception e) {
            System.err.println("[AuthRestClient] Erreur getAllUsers: " + e.getMessage());
        }
        return new ArrayList<>();
    }
    public boolean updatePassword(String username, String newPassword) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "newPassword", newPassword
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/users/" + username + "/password"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[AuthRestClient] Erreur updatePassword: " + e.getMessage());
        }
        return false;
    }
}
