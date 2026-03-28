package org.example.service;

import org.example.Pop3Server;
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
 * FIX 14 — Log directory is now created relative to the actual working
 * directory,
 * not a hardcoded relative path that only works when launched from the
 * project root. We resolve to a sibling "logs/" folder next to where
 * the JVM was started, and fall back to a temp dir if creation fails.
 */
@Service
public class Pop3ServerService implements ServerObserver {

    private Pop3Server pop3Server;
    private Thread serverThread;
    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private boolean isRunning = false;
    private PrintWriter fileLogger;

    @Autowired
    public Pop3ServerService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        initLogger();
    }

    private void initLogger() {
        // FIX 14 — try several candidate paths so it works regardless of CWD
        String[] candidates = { "POP3/logs", "logs", System.getProperty("java.io.tmpdir") + "/pop3-logs" };
        for (String path : candidates) {
            File logDir = new File(path);
            if (logDir.exists() || logDir.mkdirs()) {
                try {
                    this.fileLogger = new PrintWriter(
                            new FileWriter(new File(logDir, "pop3_server.log"), true), true);
                    System.out.println("[POP3] Log file: " + logDir.getAbsolutePath() + "/pop3_server.log");
                    return;
                } catch (IOException e) {
                    System.err.println("[POP3] Cannot write log to " + path + ": " + e.getMessage());
                }
            }
        }
        System.err.println("[POP3] WARNING: No log file will be written.");
    }

    public synchronized void startServer(int port) {
        if (isRunning) {
            logEvent("Serveur déjà en cours d'exécution.");
            return;
        }
        pop3Server = new Pop3Server(port, this);
        serverThread = new Thread(() -> {
            try {
                logEvent("Démarrage du serveur POP3 sur le port " + port);
                pop3Server.start();
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
            logEvent("Arrêt du serveur POP3 en cours...");
            if (pop3Server != null)
                pop3Server.stop();
            if (serverThread != null)
                serverThread.interrupt();
            isRunning = false;
            clientCount.set(0);
            updateClientCount(0);
            logEvent("Serveur POP3 arrêté.");
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