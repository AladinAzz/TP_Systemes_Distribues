package org.example.controller;

import org.example.client.JwtManager;
import org.example.db.EmailRecord;
import org.example.db.EmailRepository;
import org.example.service.Pop3ServerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/pop3")
public class Pop3RestController {

    private final EmailRepository emailRepository;
    private final Pop3ServerService pop3ServerService;
    private final JwtManager jwtManager = new JwtManager();

    public Pop3RestController(EmailRepository emailRepository, Pop3ServerService pop3ServerService) {
        this.emailRepository = emailRepository;
        this.pop3ServerService = pop3ServerService;
    }

    @GetMapping("/messages")
    public ResponseEntity<?> getMessages(@RequestHeader("Authorization") String authHeader) {
        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            pop3ServerService.logEvent("[REST POP3] Unauthorized access to /messages");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<EmailRecord> emails = emailRepository.fetchEmails(username);
        pop3ServerService.logEvent("[REST POP3] /messages user=" + username + " count=" + emails.size());
        return ResponseEntity.ok(emails);
    }

    @GetMapping("/messages/sent")
    public ResponseEntity<?> getSentMessages(@RequestHeader("Authorization") String authHeader) {
        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            pop3ServerService.logEvent("[REST POP3] Unauthorized access to /messages/sent");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<EmailRecord> emails = emailRepository.fetchSentEmails(username);
        pop3ServerService.logEvent("[REST POP3] /messages/sent user=" + username + " count=" + emails.size());
        return ResponseEntity.ok(emails);
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<?> getMessage(@RequestHeader("Authorization") String authHeader, @PathVariable("id") int id) {
        String username = normalizeMailbox(validateToken(authHeader));
        if (username == null) {
            pop3ServerService.logEvent("[REST POP3] Unauthorized access to /messages/" + id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // POP3 specific retrieval: fetch and return the message content
        // As requested by the user, we do NOT delete the email after viewing it via REST.
        EmailRecord email = Stream.concat(
                        emailRepository.fetchEmails(username).stream(),
                        emailRepository.fetchSentEmails(username).stream())
                .filter(e -> e.getId() == id)
                .findFirst()
                .orElse(null);

        if (email == null) {
            pop3ServerService.logEvent("[REST POP3] /messages/" + id + " user=" + username + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        pop3ServerService.logEvent("[REST POP3] /messages/" + id + " user=" + username + " found");
        return ResponseEntity.ok(email);
    }

    private String validateToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        return jwtManager.validateTokenAndGetUsername(token);
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
