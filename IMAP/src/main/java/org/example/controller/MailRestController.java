package org.example.controller;

import org.example.client.JwtManager;
import org.example.db.EmailRecord;
import org.example.db.EmailRepository;
import org.example.service.ImapServerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/imap")
public class MailRestController {

    private final EmailRepository emailRepository;
    private final ImapServerService imapServerService;
    private final JwtManager jwtManager = new JwtManager();

    public MailRestController(EmailRepository emailRepository, ImapServerService imapServerService) {
        this.emailRepository = emailRepository;
        this.imapServerService = imapServerService;
    }

    @GetMapping("/inbox")
    public ResponseEntity<?> getInbox(@RequestHeader("Authorization") String authHeader) {
        ResponseEntity<?> serverStatus = ensureImapServerRunning();
        if (serverStatus != null) {
            imapServerService.logEvent("[REST IMAP] /inbox requested while IMAP server stopped");
            return serverStatus;
        }

        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            imapServerService.logEvent("[REST IMAP] Unauthorized access to /inbox");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<EmailRecord> emails = emailRepository.fetchEmails(username);
        imapServerService.logEvent("[REST IMAP] /inbox user=" + username + " count=" + emails.size());
        return ResponseEntity.ok(emails);
    }

    @GetMapping("/inbox/sent")
    public ResponseEntity<?> getSent(@RequestHeader("Authorization") String authHeader) {
        ResponseEntity<?> serverStatus = ensureImapServerRunning();
        if (serverStatus != null) {
            imapServerService.logEvent("[REST IMAP] /inbox/sent requested while IMAP server stopped");
            return serverStatus;
        }

        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            imapServerService.logEvent("[REST IMAP] Unauthorized access to /inbox/sent");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<EmailRecord> emails = emailRepository.fetchSentEmails(username);
        imapServerService.logEvent("[REST IMAP] /inbox/sent user=" + username + " count=" + emails.size());
        return ResponseEntity.ok(emails);
    }

    @GetMapping("/emails/{id}")
    public ResponseEntity<?> getEmail(@RequestHeader("Authorization") String authHeader, @PathVariable("id") int id) {
        ResponseEntity<?> serverStatus = ensureImapServerRunning();
        if (serverStatus != null) {
            imapServerService.logEvent("[REST IMAP] /emails/" + id + " requested while IMAP server stopped");
            return serverStatus;
        }

        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            imapServerService.logEvent("[REST IMAP] Unauthorized access to /emails/" + id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Simple validation: fetch all user emails and find the one with the matching ID
        // In a production app, we'd have a specific findById(id) check for ownership
        EmailRecord email = Stream.concat(
                        emailRepository.fetchEmails(username).stream(),
                        emailRepository.fetchSentEmails(username).stream())
                .filter(e -> e.getId() == id)
                .findFirst()
                .orElse(null);

        if (email == null) {
            imapServerService.logEvent("[REST IMAP] /emails/" + id + " user=" + username + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        imapServerService.logEvent("[REST IMAP] /emails/" + id + " user=" + username + " found");
        return ResponseEntity.ok(email);
    }

    @PatchMapping("/emails/{id}/read")
    public ResponseEntity<?> markAsRead(@RequestHeader("Authorization") String authHeader, @PathVariable("id") int id) {
        ResponseEntity<?> serverStatus = ensureImapServerRunning();
        if (serverStatus != null) {
            imapServerService.logEvent("[REST IMAP] /emails/" + id + "/read requested while IMAP server stopped");
            return serverStatus;
        }

        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            imapServerService.logEvent("[REST IMAP] Unauthorized access to /emails/" + id + "/read");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        EmailRecord email = emailRepository.fetchEmails(username).stream()
                .filter(e -> e.getId() == id)
                .findFirst()
                .orElse(null);

        if (email == null) {
            imapServerService.logEvent("[REST IMAP] /emails/" + id + "/read user=" + username + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String currentFlags = email.getFlags() != null ? email.getFlags() : "";
        if (!currentFlags.contains("\\Seen")) {
            String newFlags = (currentFlags.isEmpty() ? "\\Seen" : currentFlags + " \\Seen");
            emailRepository.updateFlags(id, newFlags);
        }

        imapServerService.logEvent("[REST IMAP] /emails/" + id + "/read user=" + username + " updated");
        return ResponseEntity.ok(Map.of("message", "Email marked as read"));
    }

    @DeleteMapping("/emails/{id}")
    public ResponseEntity<?> deleteEmail(@RequestHeader("Authorization") String authHeader, @PathVariable("id") int id) {
        ResponseEntity<?> serverStatus = ensureImapServerRunning();
        if (serverStatus != null) {
            imapServerService.logEvent("[REST IMAP] /emails/" + id + " DELETE requested while IMAP server stopped");
            return serverStatus;
        }

        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            imapServerService.logEvent("[REST IMAP] Unauthorized access to DELETE /emails/" + id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Ownership check
        boolean owned = emailRepository.fetchEmails(username).stream().anyMatch(e -> e.getId() == id);
        if (!owned) {
            imapServerService.logEvent("[REST IMAP] DELETE /emails/" + id + " user=" + username + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (emailRepository.deleteEmail(id)) {
            imapServerService.logEvent("[REST IMAP] DELETE /emails/" + id + " user=" + username + " deleted");
            return ResponseEntity.ok(Map.of("message", "Email deleted"));
        }
        imapServerService.logEvent("[REST IMAP] DELETE /emails/" + id + " user=" + username + " failed");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    private String validateToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        return jwtManager.validateTokenAndGetUsername(token);
    }

    private ResponseEntity<?> ensureImapServerRunning() {
        if (!imapServerService.isRunning()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IMAP server is not running. Start it from the admin UI."));
        }
        return null;
    }

    private String normalizeMailbox(String username) {
        if (username == null) {
            return null;
        }
        int at = username.indexOf('@');
        String mailbox = at > 0 ? username.substring(0, at) : username;
        return mailbox.trim().toLowerCase();
    }
}
