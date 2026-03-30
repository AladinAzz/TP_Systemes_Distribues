package org.example.web.controller;

import org.example.web.service.MailApiClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class WebController {
    private static final String JWT_SESSION_KEY = "JWT_TOKEN";
    private static final String USERNAME_SESSION_KEY = "USERNAME";

    private final MailApiClient mailApiClient;

    public WebController(MailApiClient mailApiClient) {
        this.mailApiClient = mailApiClient;
    }

    @GetMapping("/")
    public String landing(Model model, HttpSession session) {
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        model.addAttribute("error", session.getAttribute("AUTH_ERROR"));
        addServiceStates(model);
        session.removeAttribute("AUTH_ERROR");
        return "landing";
    }

    @PostMapping("/login")
    public String login(@RequestParam("username") String username, @RequestParam("password") String password, HttpSession session) {
        String token = mailApiClient.authenticate(username, password);
        if (token == null) {
            session.setAttribute("AUTH_ERROR", "Invalid username or password.");
            return "redirect:/";
        }
        session.setAttribute(JWT_SESSION_KEY, token);
        session.setAttribute(USERNAME_SESSION_KEY, username);
        return "redirect:/imap";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    @GetMapping("/imap")
    public String imapDashboard(Model model, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        List<Map<String, Object>> emails = mailApiClient.getImapInbox(token);
        model.addAttribute("emails", emails);
        model.addAttribute("activeTab", "received");
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        addServiceStates(model);
        return "imap_dashboard";
    }

    @GetMapping("/imap/sent")
    public String imapSentDashboard(Model model, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        List<Map<String, Object>> emails = mailApiClient.getImapSent(token);
        model.addAttribute("emails", emails);
        model.addAttribute("activeTab", "sent");
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        addServiceStates(model);
        return "imap_dashboard";
    }

    @GetMapping("/imap/email/{id}")
    public String imapEmailDetail(@PathVariable("id") int id, Model model, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        Map<String, Object> email = mailApiClient.getImapEmail(token, id);
        model.addAttribute("email", email);
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        addServiceStates(model);
        return "imap_email";
    }

    @PostMapping("/imap/email/{id}/read")
    public String markImapRead(@PathVariable("id") int id, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        mailApiClient.markImapRead(token, id);
        return "redirect:/imap/email/" + id;
    }

    @PostMapping("/imap/email/{id}/delete")
    public String deleteImapEmail(@PathVariable("id") int id, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        mailApiClient.deleteImapEmail(token, id);
        return "redirect:/imap";
    }

    @GetMapping("/pop3")
    public String pop3Dashboard(Model model, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        List<Map<String, Object>> messages = mailApiClient.getPop3Messages(token);
        model.addAttribute("messages", messages);
        model.addAttribute("activeTab", "received");
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        addServiceStates(model);
        return "pop3_dashboard";
    }

    @GetMapping("/pop3/sent")
    public String pop3SentDashboard(Model model, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        List<Map<String, Object>> messages = mailApiClient.getPop3SentMessages(token);
        model.addAttribute("messages", messages);
        model.addAttribute("activeTab", "sent");
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        addServiceStates(model);
        return "pop3_dashboard";
    }

    @GetMapping("/pop3/message/{id}")
    public String pop3MessageDetail(@PathVariable("id") int id, Model model, HttpSession session) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        Map<String, Object> message = mailApiClient.getPop3Message(token, id);
        model.addAttribute("message", message);
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        addServiceStates(model);
        return "pop3_message";
    }

    @GetMapping("/compose")
    public String composePage(Model model, HttpSession session) {
        model.addAttribute("username", session.getAttribute(USERNAME_SESSION_KEY));
        model.addAttribute("status", session.getAttribute("SEND_STATUS"));
        addServiceStates(model);
        session.removeAttribute("SEND_STATUS");
        return "compose";
    }

    @PostMapping("/compose/send")
    public String sendMail(
            @RequestParam("recipients") String recipients,
            @RequestParam("subject") String subject,
            @RequestParam("body") String body,
            HttpSession session
    ) {
        String token = (String) session.getAttribute(JWT_SESSION_KEY);
        List<String> recipientList = Arrays.stream(recipients.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        boolean sent = mailApiClient.sendEmail(token, recipientList, subject, body);
        session.setAttribute("SEND_STATUS", sent ? "Message sent." : "Failed to send message.");
        return "redirect:/compose";
    }

    private void addServiceStates(Model model) {
        model.addAttribute("serviceStates", mailApiClient.getServiceStates());
    }
}
