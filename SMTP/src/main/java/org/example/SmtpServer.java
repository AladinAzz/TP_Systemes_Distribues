package org.example;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

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
        if (observer != null) observer.logEvent("Serveur SMTP en écoute sur le port " + port);
        
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                String clientIp = clientSocket.getInetAddress().getHostAddress();
                if (observer != null) observer.logEvent("Nouvelle connexion client: " + clientIp);
                
                SmtpSession session = new SmtpSession(clientSocket, observer, clientIp, this);
                activeSessions.add(session);
                session.start();
            } catch (SocketException e) {
                if (running) {
                    if (observer != null) observer.logEvent("Erreur d'acceptation de socket: " + e.getMessage());
                } else {
                    if (observer != null) observer.logEvent("Boucle d'acceptation arrêtée.");
                }
            }
        }
    }
    
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            if (observer != null) observer.logEvent("Erreur lors de la fermeture du serveur: " + e.getMessage());
        }
        
        // Fermer les connexions actives
        synchronized (activeSessions) {
            for (SmtpSession session : activeSessions) {
                session.interruptSession();
            }
            activeSessions.clear();
        }
    }
    
    public void removeSession(SmtpSession session) {
        activeSessions.remove(session);
    }
    
    // Ancien main conservé pour rétrocompatibilité si testé directement
    public static void main(String[] args) {
        try {
            new SmtpServer(25, null).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class SmtpSession extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final ServerObserver observer;
    private final String clientIp;
    private final SmtpServer server;
    private volatile boolean interrupted = false;

    // Finite state machine for the SMTP session
    private enum SmtpState {
        CONNECTED,    // Connection established; waiting for HELO/EHLO.
        HELO_RECEIVED, // HELO/EHLO received; ready for MAIL FROM.
        MAIL_FROM_SET, // MAIL FROM command processed; ready for RCPT TO.
        RCPT_TO_SET,   // At least one RCPT TO received; ready for DATA.
        DATA_RECEIVING // DATA command received; reading email content.
    }

    private SmtpState state;
    private String sender;
    private List<String> recipients;
    private StringBuilder dataBuffer;

    public SmtpSession(Socket socket) {
        this(socket, null, socket.getInetAddress().getHostAddress(), null);
    }
    
    public SmtpSession(Socket socket, ServerObserver observer, String clientIp, SmtpServer server) {
        this.socket = socket;
        this.observer = observer;
        this.clientIp = clientIp;
        this.server = server;
        this.state = SmtpState.CONNECTED;
        this.recipients = new ArrayList<>();
        this.dataBuffer = new StringBuilder();
    }
    
    public void interruptSession() {
        interrupted = true;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignorer
        }
        this.interrupt();
    }
    
    private void sendResponse(String response) {
        if (!interrupted) {
            out.println(response);
            if (observer != null) observer.logResponse(response);
        }
    }

    @Override
    public void run() {
        try {
            if (observer != null && observer instanceof org.example.service.SmtpServerService) {
                ((org.example.service.SmtpServerService) observer).incrementClient();
            }
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // Send initial greeting (RFC 5321 specifies a 220 response)
            sendResponse("220 smtp.example.com Service Ready");

            String line;
            while (!interrupted && (line = in.readLine()) != null) {
                // System.out.println("Received: " + line);
                if (observer != null) {
                    if (state == SmtpState.DATA_RECEIVING && !line.equals(".")) {
                        // Ne pas logger tout le contenu du message pour ne pas inonder la GUI
                    } else {
                        observer.logRequest(clientIp, line);
                    }
                }
                
                // If we are in DATA receiving state, accumulate message lines
                if (state == SmtpState.DATA_RECEIVING) {
                    // End of DATA input is signaled by a single dot on a line.
                    if (line.equals(".")) {
                        // Store the email and reset for next message.
                        storeEmail(dataBuffer.toString());
                        dataBuffer.setLength(0);
                        // After DATA, we allow additional RCPT TO commands for new messages,
                        // or can reset to HELO_RECEIVED depending on design.
                        state = SmtpState.HELO_RECEIVED;
                        sendResponse("250 OK: Message accepted for delivery");
                    } else {
                        // RFC 5321 Section 4.5.2 - Transparency (dot-stuffing)
                        // If a line starts with a period, remove the leading period.
                        if (line.startsWith(".")) {
                            line = line.substring(1);
                        }
                        dataBuffer.append(line).append("\r\n");
                    }
                    continue;
                }

                // Process commands outside of DATA state.
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
                        return; // Terminate session after QUIT.
                    default:
                        sendResponse("500 Command unrecognized");
                        break;
                }
            }
            // Si la boucle se termine alors que nous étions en train de recevoir les données,
            // cela signifie que la connexion a été interrompue avant la réception du point final.
            if (state == SmtpState.DATA_RECEIVING) {
                if (observer != null) observer.logEvent("Connexion interrompue durant DATA. Email non sauvegardé.");
            }
        } catch (IOException e) {
            if (!interrupted && observer != null) {
                observer.logEvent("Erreur de session avec " + clientIp + ": " + e.getMessage());
            }
        } finally {
            try { socket.close(); } catch (IOException e) { /* ignore */ }
            if (server != null) server.removeSession(this);
            if (observer != null) {
                observer.logEvent("Déconnexion client: " + clientIp);
                if (observer instanceof org.example.service.SmtpServerService) {
                    ((org.example.service.SmtpServerService) observer).decrementClient();
                }
            }
        }
    }

    private void handleHelo(String arg) {
        // Reset any previous session data
        state = SmtpState.HELO_RECEIVED;
        sender = "";
        recipients.clear();
        sendResponse("250 Hello " + arg);
    }

    private void handleEhlo(String arg) {
        // RFC 5321 Section 4.1.1.1 - EHLO must return multiline 250
        state = SmtpState.HELO_RECEIVED;
        sender = "";
        recipients.clear();
        sendResponse("250-smtp.example.com Hello " + arg);
        sendResponse("250 OK");
    }

    private void handleMailFrom(String arg) {
        // RFC 5321: MAIL FROM requires HELO/EHLO first
        if (state == SmtpState.CONNECTED) {
            sendResponse("503 Bad sequence of commands");
            return;
        }
        // Vérifier que l'argument correspond exactement au format "FROM:<email>"
        if (!arg.toUpperCase().matches("^FROM:\\s*<[^>]+>$")) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }
        // Extraire l'adresse email en retirant "FROM:" et les chevrons.
        String potentialEmail = arg.substring(5).trim();  // Extrait ce qui suit "FROM:"
        // Retirer les chevrons (< et >)
        potentialEmail = potentialEmail.substring(1, potentialEmail.length() - 1).trim();

        String email = extractEmail(potentialEmail);
        if (email == null) {
            sendResponse("501 Syntax error in parameters or arguments");
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

        // Check if the recipient's directory exists.
        // The user directory is assumed to be "mailserver/username" where username is the part before '@'.
        String username = email.split("@")[0];
        
        File mailserverDir = new File("mailserver");
        if (!mailserverDir.exists()) {
            mailserverDir = new File("../mailserver");
        }
        
        File userDir = new File(mailserverDir, username);
        if (!userDir.exists()) {
            boolean created = userDir.mkdirs();  // Create user directory
            if (!created) {
                sendResponse("550 Failed to create user directory");
                return;
            }
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

    // RFC 5321 - RSET command: abort current mail transaction
    private void handleRset() {
        sender = "";
        recipients.clear();
        dataBuffer.setLength(0);
        if (state != SmtpState.CONNECTED) {
            state = SmtpState.HELO_RECEIVED;
        }
        sendResponse("250 OK");
    }

    // RFC 5321 - NOOP command: do nothing, return OK
    private void handleNoop() {
        sendResponse("250 OK");
    }

    private void handleQuit() {
        sendResponse("221 smtp.example.com Service closing transmission channel");
    }

    // Helper to extract the first token (command) from the input line.
    private String extractToken(String line) {
        String[] parts = line.split(" ");
        return parts.length > 0 ? parts[0] : "";
    }

    // Helper to extract the argument portion (everything after the command).
    private String extractArgument(String line) {
        int index = line.indexOf(' ');
        return index > 0 ? line.substring(index).trim() : "";
    }

    // Simple email extraction: removes angle brackets and performs a basic validation.
    private String extractEmail(String input) {
        // Remove any surrounding angle brackets.
        input = input.replaceAll("[<>]", "");
        if (input.contains("@") && input.indexOf("@") > 0 && input.indexOf("@") < input.length() - 1) {
            return input;
        }
        return null;
    }

    // Store the email for each recipient in the corresponding user directory.
    // Files are named using the current timestamp.

    private void storeEmail(String data) {
        // Use a readable timestamp format (YYYYMMDD_HHMMSS)
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        File mailserverDir = new File("mailserver");
        if (!mailserverDir.exists()) {
            mailserverDir = new File("../mailserver");
        }

        for (String recipient : recipients) {
            // Extract username (before @)
            String username = recipient.split("@")[0];

            // Define user directory path
            File userDir = new File(mailserverDir, username);

            // Ensure the directory exists
            if (!userDir.exists()) {
                userDir.mkdirs();  // Create if missing
            }

            // Define email file path
            File emailFile = new File(userDir, timestamp + ".txt");

            // Write email content
            try (PrintWriter writer = new PrintWriter(new FileWriter(emailFile))) {
                // Basic email headers (RFC 5322)
                writer.println("From: " + sender);
                writer.println("To: " + String.join(", ", recipients));
                writer.println("Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(new Date()));
                writer.println("Subject: Test Email");
                writer.println();
                writer.print(data);

                // Log success
                if (observer != null) observer.logEvent("Email stocké pour " + recipient + " dans " + emailFile.getName());
            } catch (IOException e) {
                if (observer != null) observer.logEvent("Erreur stockage email: " + e.getMessage());
            }
        }
    }
}
