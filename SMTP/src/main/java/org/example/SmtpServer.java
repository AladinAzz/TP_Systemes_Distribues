package org.example;

import java.io.*;
import java.net.*;
import java.rmi.Naming;
import java.text.SimpleDateFormat;
import java.util.*;

import org.example.rmi.IAuthService;
import org.example.service.ServerObserver;

public class SmtpServer {
    private final int port;
    private final ServerObserver observer;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final List<SmtpSession> activeSessions = Collections.synchronizedList(new ArrayList<>());

    public SmtpServer(int port, ServerObserver observer) {
        this.port = port;
        this.observer = observer;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        if (observer != null)
            observer.logEvent("Serveur SMTP en écoute sur le port " + port);

        while (running) {
            try {
                Socket client = serverSocket.accept();
                String ip = client.getInetAddress().getHostAddress();
                if (observer != null)
                    observer.logEvent("Nouvelle connexion client: " + ip);
                SmtpSession session = new SmtpSession(client, observer, ip, this);
                activeSessions.add(session);
                session.start();
            } catch (SocketException e) {
                if (running && observer != null)
                    observer.logEvent("Erreur d'acceptation: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed())
                serverSocket.close();
        } catch (IOException e) {
            if (observer != null)
                observer.logEvent("Erreur fermeture: " + e.getMessage());
        }
        synchronized (activeSessions) {
            activeSessions.forEach(SmtpSession::interruptSession);
            activeSessions.clear();
        }
    }

    public void removeSession(SmtpSession s) {
        activeSessions.remove(s);
    }

    public static void main(String[] args) {
        try {
            new SmtpServer(25, null).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

class SmtpSession extends Thread {

    // FIX 9 — Cache the RMI stub; looked up once per session, not per command.
    private IAuthService authService;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final ServerObserver observer;
    private final String clientIp;
    private final SmtpServer server;
    private volatile boolean interrupted = false;

    private enum SmtpState {
        CONNECTED, HELO_RECEIVED, MAIL_FROM_SET, RCPT_TO_SET, DATA_RECEIVING
    }

    private SmtpState state;
    private String sender;
    private List<String> recipients;
    private StringBuilder dataBuffer;

    public SmtpSession(Socket socket, ServerObserver observer,
            String clientIp, SmtpServer server) {
        this.socket = socket;
        this.observer = observer;
        this.clientIp = clientIp;
        this.server = server;
        this.state = SmtpState.CONNECTED;
        this.recipients = new ArrayList<>();
        this.dataBuffer = new StringBuilder();
    }

    // FIX 9 — one lookup per session
    private IAuthService getAuthService() {
        if (authService == null) {
            try {
                authService = (IAuthService) Naming.lookup("rmi://localhost:1099/AuthService");
            } catch (Exception e) {
                if (observer != null)
                    observer.logEvent("Erreur RMI lookup: " + e.getMessage());
            }
        }
        return authService;
    }

    public void interruptSession() {
        interrupted = true;
        try {
            if (socket != null && !socket.isClosed())
                socket.close();
        } catch (IOException ignored) {
        }
        this.interrupt();
    }

    private void sendResponse(String r) {
        if (!interrupted) {
            out.println(r);
            if (observer != null)
                observer.logResponse(r);
        }
    }

    @Override
    public void run() {
        try {
            if (observer instanceof org.example.service.SmtpServerService)
                ((org.example.service.SmtpServerService) observer).incrementClient();

            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendResponse("220 smtp.example.com Service Ready");

            String line;
            while (!interrupted && (line = in.readLine()) != null) {

                if (state == SmtpState.DATA_RECEIVING) {
                    if (line.equals(".")) {
                        storeEmail(dataBuffer.toString());
                        // FIX 4 — clear recipients and buffer after delivery
                        dataBuffer.setLength(0);
                        recipients.clear();
                        sender = "";
                        state = SmtpState.HELO_RECEIVED;
                        sendResponse("250 OK: Message accepted for delivery");
                    } else {
                        // RFC 5321 §4.5.2 — dot-stuffing
                        if (line.startsWith("."))
                            line = line.substring(1);
                        dataBuffer.append(line).append("\r\n");
                    }
                    continue;
                }

                if (observer != null)
                    observer.logRequest(clientIp, line);

                String command = extractToken(line).toUpperCase();
                String argument = extractArgument(line);

                switch (command) {
                    case "HELO":
                        handleHelo(argument);
                        break;
                    case "EHLO":
                        handleEhlo(argument);
                        break;
                    case "MAIL":
                        handleMailFrom(argument);
                        break;
                    case "RCPT":
                        handleRcptTo(argument);
                        break;
                    case "DATA":
                        handleData();
                        break;
                    case "RSET":
                        handleRset();
                        break;
                    case "NOOP":
                        handleNoop();
                        break;
                    case "QUIT":
                        handleQuit();
                        return;
                    default:
                        sendResponse("500 Command unrecognized");
                }
            }
        } catch (IOException e) {
            if (!interrupted && observer != null)
                observer.logEvent("Erreur session " + clientIp + ": " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (server != null)
                server.removeSession(this);
            if (observer != null) {
                observer.logEvent("Déconnexion client: " + clientIp);
                if (observer instanceof org.example.service.SmtpServerService)
                    ((org.example.service.SmtpServerService) observer).decrementClient();
            }
        }
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private void handleHelo(String arg) {
        state = SmtpState.HELO_RECEIVED;
        sender = "";
        recipients.clear();
        sendResponse("250 Hello " + arg);
    }

    // FIX 10 — separate EHLO handler returning proper multi-line 250
    private void handleEhlo(String arg) {
        state = SmtpState.HELO_RECEIVED;
        sender = "";
        recipients.clear();
        sendResponse("250-smtp.example.com Hello " + arg);
        sendResponse("250 OK");
    }

    private void handleMailFrom(String arg) {
        // FIX 5 — must have HELO/EHLO first
        if (state == SmtpState.CONNECTED) {
            sendResponse("503 Bad sequence of commands");
            return;
        }
        if (!arg.toUpperCase().matches("^FROM:\\s*<[^>]+>$")) {
            sendResponse("501 Syntax error in parameters or arguments");
            // FIX 6 — removed the debug leak: no second out.println(arg)
            return;
        }

        String potentialEmail = arg.substring(5).trim();
        potentialEmail = potentialEmail.substring(1, potentialEmail.length() - 1).trim();
        String email = extractEmail(potentialEmail);
        if (email == null) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }

        // FIX 9 — use cached RMI stub
        IAuthService svc = getAuthService();
        if (svc == null) {
            sendResponse("451 Local error: RMI unavailable");
            return;
        }
        try {
            String username = email.split("@")[0];
            if (!svc.userExists(username)) {
                sendResponse("550 User unknown / Non autorisé");
                if (observer != null)
                    observer.logEvent("Rejet SMTP: '" + username + "' inconnu du registre RMI.");
                return;
            }
        } catch (Exception e) {
            if (observer != null)
                observer.logEvent("Erreur RMI (MAIL FROM): " + e.getMessage());
            sendResponse("451 Local error in processing (RMI)");
            return;
        }

        sender = email;
        state = SmtpState.MAIL_FROM_SET;
        sendResponse("250 OK");
    }

    private void handleRcptTo(String arg) {
        if (state != SmtpState.MAIL_FROM_SET && state != SmtpState.RCPT_TO_SET) {
            sendResponse("503 Bad sequence of commands");
            return;
        }
        if (!arg.toUpperCase().startsWith("TO:")) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }
        String potentialEmail = arg.substring(3).trim();
        String email = extractEmail(potentialEmail);
        if (email == null) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }

        String username = email.split("@")[0];
        File mailserverDir = resolveMailserverDir();
        File userDir = new File(mailserverDir, username);
        if (!userDir.exists() && !userDir.mkdirs()) {
            sendResponse("550 Failed to create mailbox directory");
            return;
        }

        recipients.add(email);
        state = SmtpState.RCPT_TO_SET;
        sendResponse("250 OK");
    }

    private void handleData() {
        if (state != SmtpState.RCPT_TO_SET || recipients.isEmpty()) {
            sendResponse("503 Bad sequence of commands");
            return;
        }
        state = SmtpState.DATA_RECEIVING;
        sendResponse("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    private void handleRset() {
        sender = "";
        recipients.clear();
        dataBuffer.setLength(0);
        if (state != SmtpState.CONNECTED)
            state = SmtpState.HELO_RECEIVED;
        sendResponse("250 OK");
    }

    private void handleNoop() {
        sendResponse("250 OK");
    }

    private void handleQuit() {
        sendResponse("221 smtp.example.com Service closing transmission channel");
    }

    // ── Email storage ─────────────────────────────────────────────────────────

    /**
     * FIX 3 — Parse subject from the DATA buffer instead of hardcoding
     * "Test Email". If no Subject header is found in the body,
     * fall back to "(no subject)".
     */
    private void storeEmail(String data) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mailserverDir = resolveMailserverDir();

        // Extract subject from the buffered DATA
        String subject = "(no subject)";
        for (String line : data.split("\r\n")) {
            if (line.trim().isEmpty())
                break; // end of headers
            if (line.toLowerCase().startsWith("subject:")) {
                subject = line.substring(8).trim();
                break;
            }
        }

        for (String recipient : recipients) {
            String username = recipient.split("@")[0];
            File userDir = new File(mailserverDir, username);
            if (!userDir.exists())
                userDir.mkdirs();

            File emailFile = new File(userDir, timestamp + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(emailFile))) {
                // RFC 5322 envelope headers
                writer.println("From: " + sender);
                writer.println("To: " + String.join(", ", recipients));
                writer.println("Date: " +
                        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(new Date()));
                writer.println("Subject: " + subject); // FIX 3
                writer.println();
                writer.print(data);
                if (observer != null)
                    observer.logEvent("Email stocké pour " + recipient +
                            " dans " + emailFile.getName());
            } catch (IOException e) {
                if (observer != null)
                    observer.logEvent("Erreur stockage email: " + e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private File resolveMailserverDir() {
        File d = new File("mailserver");
        if (!d.exists())
            d = new File("../mailserver");
        return d;
    }

    private String extractToken(String line) {
        String[] p = line.split(" ");
        return p.length > 0 ? p[0] : "";
    }

    private String extractArgument(String line) {
        int i = line.indexOf(' ');
        return i > 0 ? line.substring(i).trim() : "";
    }

    private String extractEmail(String input) {
        input = input.replaceAll("[<>]", "").trim();
        int at = input.indexOf('@');
        if (at > 0 && at < input.length() - 1)
            return input;
        return null;
    }
}