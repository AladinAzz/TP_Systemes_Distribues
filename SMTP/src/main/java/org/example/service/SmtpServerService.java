package org.example.service;

import org.example.SmtpServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SmtpServerService implements ServerObserver {

    private SmtpServer smtpServer;
    private Thread serverThread;
    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private boolean isRunning = false;
    private PrintWriter fileLogger;

    @Autowired
    public SmtpServerService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        try {
            java.io.File logDir = new java.io.File("SMTP/logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            this.fileLogger = new PrintWriter(new FileWriter("SMTP/logs/smtp_server.log", true), true);
        } catch (IOException e) {
            System.err.println("Impossible de créer le fichier de log SMTP: " + e.getMessage());
        }
    }

    public synchronized void startServer(int port) {
        if (isRunning) {
            logEvent("Serveur déjà en cours d'exécution.");
            return;
        }

        smtpServer = new SmtpServer(port, this);
        serverThread = new Thread(() -> {
            try {
                logEvent("Démarrage du serveur SMTP sur le port " + port);
                isRunning = true;
                smtpServer.start();
            } catch (Exception e) {
                logEvent("Erreur lors du démarrage du serveur: " + e.getMessage());
                isRunning = false;
            }
        });
        serverThread.start();
    }

    public synchronized void stopServer() {
        if (!isRunning) {
            logEvent("Le serveur n'est pas en cours d'exécution.");
            return;
        }

        try {
            logEvent("Arrêt du serveur SMTP en cours...");
            if (smtpServer != null) {
                smtpServer.stop();
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
            isRunning = false;
            clientCount.set(0);
            updateClientCount(0);
            logEvent("Serveur SMTP arrêté.");
        } catch (Exception e) {
            logEvent("Erreur lors de l'arrêt du serveur: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getClientCount() {
        return clientCount.get();
    }

    private String getTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private String getLogPrefix() {
        return String.format("[%s] [%s]", getTimestamp(), System.getProperty("user.name", "UnknownUser"));
    }

    private void writeToFile(String logText) {
        if (fileLogger != null) {
            fileLogger.println(logText);
        }
    }

    @Override
    public void logRequest(String clientEnv, String message) {
        String uiLog = String.format("[%s] Client (%s) -> %s", new SimpleDateFormat("HH:mm:ss").format(new Date()), clientEnv, message);
        String fileLog = String.format("%s Client (%s) -> %s", getLogPrefix(), clientEnv, message);
        messagingTemplate.convertAndSend("/topic/logs", uiLog);
        writeToFile(fileLog);
    }

    @Override
    public void logResponse(String message) {
        String uiLog = String.format("[%s] Serveur -> %s", new SimpleDateFormat("HH:mm:ss").format(new Date()), message);
        String fileLog = String.format("%s Serveur -> %s", getLogPrefix(), message);
        messagingTemplate.convertAndSend("/topic/logs", uiLog);
        writeToFile(fileLog);
    }

    @Override
    public void logEvent(String message) {
        String uiLog = String.format("[%s] INFO -> %s", new SimpleDateFormat("HH:mm:ss").format(new Date()), message);
        String fileLog = String.format("%s INFO -> %s", getLogPrefix(), message);
        messagingTemplate.convertAndSend("/topic/logs", uiLog);
        writeToFile(fileLog);
    }

    @Override
    public void updateClientCount(int count) {
        clientCount.set(count);
        messagingTemplate.convertAndSend("/topic/clients", count);
    }
    
    public void incrementClient() {
        updateClientCount(clientCount.incrementAndGet());
    }
    
    public void decrementClient() {
        updateClientCount(clientCount.decrementAndGet());
    }
}
