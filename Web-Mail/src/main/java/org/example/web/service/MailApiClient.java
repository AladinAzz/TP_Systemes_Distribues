package org.example.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.client.AuthRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MailApiClient {
    private final AuthRestClient authRestClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String smtpApiBase;
    private final String imapApiBase;
    private final String pop3ApiBase;

    public MailApiClient(
            @Value("${mail.api.smtp-base:http://localhost:8080/api/mail}") String smtpApiBase,
            @Value("${mail.api.imap-base:http://localhost:8082/api/imap}") String imapApiBase,
            @Value("${mail.api.pop3-base:http://localhost:8081/api/pop3}") String pop3ApiBase
    ) {
        this.authRestClient = new AuthRestClient();
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.smtpApiBase = smtpApiBase;
        this.imapApiBase = imapApiBase;
        this.pop3ApiBase = pop3ApiBase;
    }

    public String authenticate(String username, String password) {
        return authRestClient.authenticate(username, password);
    }

    public List<Map<String, Object>> getImapInbox(String token) {
        return getList(token, imapApiBase + "/inbox");
    }

    public List<Map<String, Object>> getImapSent(String token) {
        return getList(token, imapApiBase + "/inbox/sent");
    }

    public Map<String, Object> getImapEmail(String token, int id) {
        return getObject(token, imapApiBase + "/emails/" + id);
    }

    public boolean markImapRead(String token, int id) {
        return sendNoBody(token, "PATCH", imapApiBase + "/emails/" + id + "/read");
    }

    public boolean deleteImapEmail(String token, int id) {
        return sendNoBody(token, "DELETE", imapApiBase + "/emails/" + id);
    }

    public List<Map<String, Object>> getPop3Messages(String token) {
        return getList(token, pop3ApiBase + "/messages");
    }

    public List<Map<String, Object>> getPop3SentMessages(String token) {
        return getList(token, pop3ApiBase + "/messages/sent");
    }

    public Map<String, Object> getPop3Message(String token, int id) {
        return getObject(token, pop3ApiBase + "/messages/" + id);
    }

    public boolean sendEmail(String token, List<String> recipients, String subject, String body) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("recipients", recipients);
            payload.put("subject", subject);
            payload.put("body", body);

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(smtpApiBase + "/send"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, String> getServiceStates() {
        Map<String, String> states = new HashMap<>();
        states.put("smtp", fetchServiceState(smtpApiBase));
        states.put("pop3", fetchServiceState(pop3ApiBase));
        states.put("imap", fetchServiceState(imapApiBase));
        return states;
    }

    private List<Map<String, Object>> getList(String token, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() {});
            }
            System.err.println("[MailApiClient] GET failed (" + response.statusCode() + "): " + url);
        } catch (Exception ignored) {
            System.err.println("[MailApiClient] GET exception for " + url + ": " + ignored.getMessage());
        }
        return Collections.emptyList();
    }

    private Map<String, Object> getObject(String token, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            }
            System.err.println("[MailApiClient] GET failed (" + response.statusCode() + "): " + url);
        } catch (Exception ignored) {
            System.err.println("[MailApiClient] GET exception for " + url + ": " + ignored.getMessage());
        }
        return Collections.emptyMap();
    }

    private boolean sendNoBody(String token, String method, String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token);

            if ("DELETE".equals(method)) {
                builder.DELETE();
            } else if ("PATCH".equals(method)) {
                builder.method("PATCH", HttpRequest.BodyPublishers.noBody());
            } else {
                return false;
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    private String fetchServiceState(String apiBase) {
        String url = statusUrlFromApiBase(apiBase);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
                Object running = payload.get("running");
                if (running instanceof Boolean b) {
                    return b ? "RUNNING" : "STOPPED";
                }
            }
            System.err.println("[MailApiClient] Status check failed (" + response.statusCode() + "): " + url);
        } catch (Exception e) {
            System.err.println("[MailApiClient] Status check exception for " + url + ": " + e.getMessage());
        }
        return "UNREACHABLE";
    }

    private String statusUrlFromApiBase(String apiBase) {
        int apiIndex = apiBase.indexOf("/api/");
        if (apiIndex >= 0) {
            return apiBase.substring(0, apiIndex) + "/api/status";
        }
        return apiBase + "/api/status";
    }
}
