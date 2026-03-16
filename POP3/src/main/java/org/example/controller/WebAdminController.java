package org.example.controller;

import org.example.service.Pop3ServerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/")
public class WebAdminController {

    private final Pop3ServerService pop3Service;

    @Autowired
    public WebAdminController(Pop3ServerService pop3Service) {
        this.pop3Service = pop3Service;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("port", 110);
        return "index";
    }

    @PostMapping("/api/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startServer() {
        Map<String, Object> response = new HashMap<>();
        try {
            pop3Service.startServer(110);
            response.put("status", "success");
            response.put("message", "Serveur démarré");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/stop")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stopServer() {
        Map<String, Object> response = new HashMap<>();
        try {
            pop3Service.stopServer();
            response.put("status", "success");
            response.put("message", "Serveur arrêté");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("running", pop3Service.isRunning());
        response.put("clientCount", pop3Service.getClientCount());
        return ResponseEntity.ok(response);
    }
}
