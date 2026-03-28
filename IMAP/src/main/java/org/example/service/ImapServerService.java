package org.example.service;

import org.example.ImapServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FIX 14 — Same log-path fix as Pop3ServerService: try multiple candidate
 * directories so the service works regardless of working directory.
 */
@Service
public class ImapServerService implements ServerObserver {

    private ImapServer imapServer;
    private Thread serverThread;
    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private boolean isRunning = false;
    private PrintWriter fileLogger;

    @Autowired
    public ImapServerService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        initLogger();
    }

    private void initLogger() {
        String[] candidates = { "IMAP/logs", "logs", System.getProperty("java.io.tmpdir") + "/imap-logs" };
        for (String path : candidates) {
            File logDir = new File(path);
            if (logDir.exists() || logDir.mkdirs()) {
                try {
                    this.fileLogger = new PrintWriter(
                            new FileWriter(new File(logDir, "imap_server.log"), true), true);
                    System.out.println("[IMAP] Log file: " + logDir.getAbsolutePath() + "/imap_server.log");
                    return;
                } catch (IOException e) {
                    System.err.println("[IMAP] Cannot write log to " + path + ": " + e.getMessage());
                }
            }
        }
        System.err.println("[IMAP] WARNING: No log file will be written.");
    }

    public synchronized void startServer(int port) {
        if (isRunning) {
            logEvent("Serveur déjà en cours d'exécution.");
            return;
        }
        imapServer = new ImapServer(port, this);
        serverThread = new Thread(() -> {
            try {
                logEvent("Démarrage du serveur IMAP sur le port " + port);
                imapServer.start();
            } catch (Exception e) {
                logEvent("Erreur lors du démarrage: " + e.getMessage());
                isRunning = false;
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        isRunning = true;
    }

    public synchronized void stopServer() {
        if (!isRunning) {
            logEvent("Le serveur n'est pas en cours d'exécution.");
            return;
        }
        try {
            logEvent("Arrêt du serveur IMAP en cours...");
            if (imapServer != null)
                imapServer.stop();
            if (serverThread != null)
                serverThread.interrupt();
            isRunning = false;
            clientCount.set(0);
            updateClientCount(0);
            logEvent("Serveur IMAP arrêté.");
        } catch (Exception e) {
            logEvent("Erreur lors de l'arrêt: " + e.getMessage());
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
        return String.format("[%s] [%s]", getTimestamp(),
                System.getProperty("user.name", "unknown"));
    }

    private void writeToFile(String text) {
        if (fileLogger != null)
            fileLogger.println(text);
    }

    @Override
    public void logRequest(String clientEnv, String message) {
        String ui = String.format("[%s] Client (%s) -> %s",
                new SimpleDateFormat("HH:mm:ss").format(new Date()), clientEnv, message);
        String log = String.format("%s Client (%s) -> %s", getLogPrefix(), clientEnv, message);
        messagingTemplate.convertAndSend("/topic/logs", ui);
        writeToFile(log);
    }

    @Override
    public void logResponse(String message) {
        String ui = String.format("[%s] Serveur -> %s",
                new SimpleDateFormat("HH:mm:ss").format(new Date()), message);
        String log = String.format("%s Serveur -> %s", getLogPrefix(), message);
        messagingTemplate.convertAndSend("/topic/logs", ui);
        writeToFile(log);
    }

    @Override
    public void logEvent(String message) {
        String ui = String.format("[%s] INFO -> %s",
                new SimpleDateFormat("HH:mm:ss").format(new Date()), message);
        String log = String.format("%s INFO -> %s", getLogPrefix(), message);
        messagingTemplate.convertAndSend("/topic/logs", ui);
        writeToFile(log);
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