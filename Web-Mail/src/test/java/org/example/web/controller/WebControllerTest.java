package org.example.web.controller;

import jakarta.servlet.http.HttpSession;
import org.example.web.service.MailApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class WebControllerTest {

    private StubMailApiClient mailApiClient;
    private WebController webController;

    @BeforeEach
    public void setup() {
        mailApiClient = new StubMailApiClient();
        webController = new WebController(mailApiClient);
    }

    @Test
    public void login_WhenCredentialsAreValid_ShouldStoreSessionAndRedirectToImap() {
        HttpSession session = new MockHttpSession();
        mailApiClient.nextToken = "jwt-token";

        String view = webController.login("alice", "secret", session);

        assertEquals("redirect:/imap", view);
        assertEquals("jwt-token", session.getAttribute("JWT_TOKEN"));
        assertEquals("alice", session.getAttribute("USERNAME"));
    }

    @Test
    public void landing_ShouldExposeAndClearAuthError() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("AUTH_ERROR", "Invalid username or password.");
        session.setAttribute("USERNAME", "alice");
        Model model = new ExtendedModelMap();

        String view = webController.landing(model, session);

        assertEquals("landing", view);
        assertEquals("alice", model.getAttribute("username"));
        assertEquals("Invalid username or password.", model.getAttribute("error"));
        assertNull(session.getAttribute("AUTH_ERROR"));
    }

    @Test
    public void sendMail_ShouldSplitRecipientsAndSetSuccessStatus() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("JWT_TOKEN", "jwt-token");
        mailApiClient.sendEmailResult = true;

        String view = webController.sendMail("a@test.com, b@test.com; c@test.com", "Hello", "Body", session);

        assertEquals("redirect:/compose", view);
        assertEquals("Message sent.", session.getAttribute("SEND_STATUS"));
        assertEquals("jwt-token", mailApiClient.lastToken);
        assertEquals(List.of("a@test.com", "b@test.com", "c@test.com"), mailApiClient.lastRecipients);
        assertEquals("Hello", mailApiClient.lastSubject);
        assertEquals("Body", mailApiClient.lastBody);
    }

    @Test
    public void imapSentDashboard_ShouldLoadSentTab() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("JWT_TOKEN", "jwt-token");
        session.setAttribute("USERNAME", "alice");
        Model model = new ExtendedModelMap();

        String view = webController.imapSentDashboard(model, session);

        assertEquals("imap_dashboard", view);
        assertEquals("sent", model.getAttribute("activeTab"));
        assertSame(mailApiClient.imapSent, model.getAttribute("emails"));
        assertEquals("jwt-token", mailApiClient.lastImapSentToken);
    }

    @Test
    public void pop3SentDashboard_ShouldLoadSentTab() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("JWT_TOKEN", "jwt-token");
        session.setAttribute("USERNAME", "alice");
        Model model = new ExtendedModelMap();

        String view = webController.pop3SentDashboard(model, session);

        assertEquals("pop3_dashboard", view);
        assertEquals("sent", model.getAttribute("activeTab"));
        assertSame(mailApiClient.pop3Sent, model.getAttribute("messages"));
        assertEquals("jwt-token", mailApiClient.lastPop3SentToken);
    }

    private static class StubMailApiClient extends MailApiClient {
        String nextToken;
        boolean sendEmailResult;
        String lastToken;
        String lastImapSentToken;
        String lastPop3SentToken;
        List<String> lastRecipients = new ArrayList<>();
        String lastSubject;
        String lastBody;
        List<Map<String, Object>> imapSent = List.of(Map.of("id", 1, "emailType", "sent"));
        List<Map<String, Object>> pop3Sent = List.of(Map.of("id", 2, "emailType", "sent"));

        StubMailApiClient() {
            super("http://localhost:8080/api/mail", "http://localhost:8082/api/imap", "http://localhost:8081/api/pop3");
        }

        @Override
        public String authenticate(String username, String password) {
            return nextToken;
        }

        @Override
        public boolean sendEmail(String token, List<String> recipients, String subject, String body) {
            this.lastToken = token;
            this.lastRecipients = new ArrayList<>(recipients);
            this.lastSubject = subject;
            this.lastBody = body;
            return sendEmailResult;
        }

        @Override
        public List<Map<String, Object>> getImapSent(String token) {
            lastImapSentToken = token;
            return imapSent;
        }

        @Override
        public List<Map<String, Object>> getPop3SentMessages(String token) {
            lastPop3SentToken = token;
            return pop3Sent;
        }

        @Override
        public Map<String, String> getServiceStates() {
            return Map.of(
                    "smtp", "RUNNING",
                    "pop3", "RUNNING",
                    "imap", "RUNNING"
            );
        }
    }
}
