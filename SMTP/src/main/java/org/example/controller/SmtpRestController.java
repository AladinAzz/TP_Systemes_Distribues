package org.example.controller;

import org.example.client.JwtManager;
import org.example.db.EmailRepository;
import org.example.service.SmtpServerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
public class SmtpRestController {

    private final EmailRepository emailRepository;
    private final SmtpServerService smtpServerService;
    private final JwtManager jwtManager = new JwtManager();

    public SmtpRestController(EmailRepository emailRepository, SmtpServerService smtpServerService) {
        this.emailRepository = emailRepository;
        this.smtpServerService = smtpServerService;
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendEmail(@RequestHeader("Authorization") String authHeader,
                                       @RequestBody Map<String, Object> request) {
        if (!smtpServerService.isRunning()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "SMTP server is not running. Start it from the admin UI."));
        }

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String token = authHeader.substring(7);
        String sender = jwtManager.validateTokenAndGetUsername(token);
        if (sender == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String subject = (String) request.get("subject");
        String body = (String) request.get("body");
        Object recipientsObj = request.get("recipients");

        if (subject == null || body == null || recipientsObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Subject, body, and recipients required"));
        }

        List<String> recipients;
        if (recipientsObj instanceof List) {
            recipients = (List<String>) recipientsObj;
        } else {
            recipients = List.of(recipientsObj.toString());
        }

        String normalizedSender = normalizeMailbox(sender);
        for (String recipient : recipients) {
            String normalizedRecipient = normalizeMailbox(recipient);
            emailRepository.storeEmail(normalizedSender, normalizedRecipient, subject, body);
        }

        return ResponseEntity.ok(Map.of("message", "Email(s) queued for delivery"));
    }

    private String normalizeMailbox(String addressOrUser) {
        if (addressOrUser == null) {
            return "";
        }
        String value = addressOrUser.replace("<", "").replace(">", "").trim();
        int at = value.indexOf('@');
        String mailbox = at > 0 ? value.substring(0, at) : value;
        return mailbox.toLowerCase();
    }
}
