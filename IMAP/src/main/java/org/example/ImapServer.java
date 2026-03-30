package org.example;

import java.io.*;
import java.net.*;
import java.util.*;

import org.example.client.AuthRestClient;
import org.example.db.EmailRecord;
import org.example.db.EmailRepository;
import org.example.service.ServerObserver;

public class ImapServer {
    private final int port;
    private final ServerObserver observer;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final List<ImapSession> activeSessions = Collections.synchronizedList(new ArrayList<>());
    private AuthRestClient authClientOverride;

    public ImapServer(int port, ServerObserver observer) {
        this.port = port;
        this.observer = observer;
    }

    public void setAuthClientOverride(AuthRestClient authClient) {
        this.authClientOverride = authClient;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        if (observer != null)
            observer.logEvent("Serveur IMAP en écoute sur le port " + port);

        while (running) {
            try {
                Socket client = serverSocket.accept();
                String ip = client.getInetAddress().getHostAddress();
                if (observer != null)
                    observer.logEvent("Nouvelle connexion client: " + ip);
                ImapSession session = new ImapSession(client, observer, ip, this);
                if (authClientOverride != null) {
                    session.setAuthClient(authClientOverride);
                }
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
            activeSessions.forEach(ImapSession::interruptSession);
            activeSessions.clear();
        }
    }

    public void removeSession(ImapSession s) {
        activeSessions.remove(s);
    }

    public static void main(String[] args) {
        try {
            new ImapServer(143, null).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

class ImapSession extends Thread {

    private AuthRestClient authClient;
    private final EmailRepository emailRepository = new EmailRepository();

    private final Socket socket;
    private BufferedReader in;
    private OutputStream rawOut;
    private final ServerObserver observer;
    private final String clientIp;
    private final ImapServer server;
    private volatile boolean interrupted = false;

    private enum ImapState {
        NOT_AUTHENTICATED, AUTHENTICATED, SELECTED, LOGOUT
    }

    private ImapState state;
    private String username;
    private List<EmailRecord> emails;
    private String selectedMailbox;

    public ImapSession(Socket socket, ServerObserver observer, String clientIp, ImapServer server) {
        this.socket = socket;
        this.observer = observer;
        this.clientIp = clientIp;
        this.server = server;
        this.state = ImapState.NOT_AUTHENTICATED;
    }

    private AuthRestClient getAuthService() {
        if (authClient == null) {
            authClient = new AuthRestClient();
        }
        return authClient;
    }

    public void setAuthClient(AuthRestClient client) {
        this.authClient = client;
    }

    public void interruptSession() {
        interrupted = true;
        try {
            if (socket != null && !socket.isClosed())
                socket.close();
        } catch (IOException ignored) {}
        this.interrupt();
    }

    private void sendLine(String line) {
        if (interrupted) return;
        try {
            rawOut.write((line + "\r\n").getBytes("UTF-8"));
            rawOut.flush();
        } catch (IOException e) {
            interrupted = true;
        }
        if (observer != null)
            observer.logResponse(line);
    }

    private void sendLiteral(String tag, int seqNum, String fetchItem, String content, String flagsIfAny) throws IOException {
        if (interrupted) return;
        byte[] contentBytes = content.getBytes("UTF-8");
        String prefix = "* " + seqNum + " FETCH (" + fetchItem + " {" + contentBytes.length + "}";
        sendLine(prefix);
        rawOut.write(contentBytes);
        rawOut.write(("\r\n)").getBytes("UTF-8"));
        if (flagsIfAny != null) {
            rawOut.write(("\r\n* " + seqNum + " FETCH (FLAGS (" + flagsIfAny + "))").getBytes("UTF-8"));
        }
        rawOut.write("\r\n".getBytes("UTF-8"));
        rawOut.flush();
        sendLine(tag + " OK FETCH completed");
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            rawOut = socket.getOutputStream();

            sendLine("* OK [CAPABILITY IMAP4rev2] IMAP4rev2 Service Ready");

            String line;
            while (!interrupted && (line = in.readLine()) != null) {
                if (observer != null)
                    observer.logRequest(clientIp, line);
                String[] parts = line.split(" ", 3);
                if (parts.length < 2) {
                    sendLine("* BAD Invalid command format");
                    continue;
                }

                String tag = parts[0];
                String command = parts[1].toUpperCase();
                String arguments = parts.length > 2 ? parts[2] : "";

                switch (command) {
                    case "CAPABILITY": handleCapability(tag); break;
                    case "NOOP": handleNoop(tag); break;
                    case "LOGIN": handleLogin(tag, arguments); break;
                    case "SELECT": handleSelect(tag, arguments); break;
                    case "FETCH": handleFetch(tag, arguments); break;
                    case "STORE": handleStore(tag, arguments); break;
                    case "SEARCH": handleSearch(tag, arguments); break;
                    case "LOGOUT": handleLogout(tag); return;
                    default: sendLine(tag + " BAD Unknown command");
                }
            }
        } catch (IOException e) {
            if (!interrupted && observer != null)
                observer.logEvent("Erreur session " + clientIp + ": " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            if (server != null) server.removeSession(this);
            if (observer != null) observer.logEvent("Déconnexion client: " + clientIp);
        }
    }

    private void handleCapability(String tag) {
        sendLine("* CAPABILITY IMAP4rev2");
        sendLine(tag + " OK CAPABILITY completed");
    }

    private void handleNoop(String tag) {
        sendLine(tag + " OK NOOP completed");
    }

    private void handleLogin(String tag, String args) {
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            sendLine(tag + " BAD LOGIN requires username and password");
            return;
        }

        String user = parts[0].replaceAll("\"", "");
        String pass = parts[1].replaceAll("\"", "");

        AuthRestClient svc = getAuthService();
        try {
            String token = svc.authenticate(user, pass);
            if (token == null) {
                sendLine(tag + " NO [AUTHENTICATIONFAILED] LOGIN failed");
                return;
            }
        } catch (Exception e) {
            sendLine(tag + " BAD Internal server error");
            return;
        }

        username = user;
        state = ImapState.AUTHENTICATED;
        sendLine(tag + " OK LOGIN completed");
    }

    private void handleSelect(String tag, String args) {
        if (state == ImapState.NOT_AUTHENTICATED) {
            sendLine(tag + " BAD Command requires authentication");
            return;
        }
        String mailbox = args.trim().replaceAll("\"", "");
        if (!mailbox.equalsIgnoreCase("INBOX")) {
            sendLine(tag + " NO [NONEXISTENT] Mailbox does not exist");
            return;
        }

        emails = emailRepository.fetchEmails(username);
        selectedMailbox = mailbox;
        state = ImapState.SELECTED;

        sendLine("* " + emails.size() + " EXISTS");
        sendLine("* 0 RECENT");
        sendLine("* FLAGS (\\Seen \\Answered \\Flagged \\Deleted \\Draft)");
        sendLine("* OK [PERMANENTFLAGS (\\Seen \\Deleted)]");
        sendLine(tag + " OK [READ-WRITE] SELECT completed");
    }

    private void handleFetch(String tag, String args) {
        if (state != ImapState.SELECTED) {
            sendLine(tag + " BAD No mailbox selected");
            return;
        }
        String[] parts = args.split(" ", 2);
        int seqNum = Integer.parseInt(parts[0]);
        EmailRecord email = emails.get(seqNum - 1);
        String upper = parts[1].toUpperCase();

        try {
            if (upper.contains("FLAGS")) {
                sendLine("* " + seqNum + " FETCH (FLAGS (" + email.getFlags() + "))");
                sendLine(tag + " OK FETCH completed");
            } else if (upper.contains("BODY[HEADER]")) {
                sendLiteral(tag, seqNum, "BODY[HEADER]", parseHeaders(email.getBody()), null);
            } else if (upper.contains("BODY[]") || upper.contains("RFC822")) {
                addFlag(email, "\\Seen");
                sendLiteral(tag, seqNum, "BODY[]", email.getBody(), null);
            } else if (upper.contains("BODY[TEXT]")) {
                sendLiteral(tag, seqNum, "BODY[TEXT]", parseBody(email.getBody()), null);
            } else {
                sendLine(tag + " BAD Unknown FETCH item");
            }
        } catch (IOException e) {
            sendLine(tag + " NO FETCH error");
        }
    }

    private void handleStore(String tag, String args) {
        String[] parts = args.split(" ", 3);
        int seqNum = Integer.parseInt(parts[0]);
        String action = parts[1].toUpperCase();
        String flagStr = parts[2].trim().replaceAll("[\\(\\)]", "");
        List<String> newFlags = Arrays.asList(flagStr.split("\\s+"));

        EmailRecord email = emails.get(seqNum - 1);
        if (action.contains("+FLAGS")) {
            newFlags.forEach(f -> addFlag(email, f));
        } else if (action.contains("-FLAGS")) {
            newFlags.forEach(f -> removeFlag(email, f));
        } else {
            setFlags(email, newFlags);
        }

        if (!action.contains("SILENT")) {
            sendLine("* " + seqNum + " FETCH (FLAGS (" + email.getFlags() + "))");
        }
        sendLine(tag + " OK STORE completed");
    }

    private void handleSearch(String tag, String args) {
        String[] parts = args.split(" ", 2);
        String criteria = parts[0].toUpperCase();
        String value = parts.length > 1 ? parts[1].replaceAll("\"", "").toLowerCase() : "";

        List<Integer> results = new ArrayList<>();
        for (int i = 0; i < emails.size(); i++) {
            EmailRecord e = emails.get(i);
            boolean match = switch (criteria) {
                case "ALL" -> true;
                case "FROM" -> e.getSender().toLowerCase().contains(value);
                case "SUBJECT" -> e.getSubject().toLowerCase().contains(value);
                case "UNSEEN" -> !e.getFlags().contains("\\Seen");
                case "SEEN" -> e.getFlags().contains("\\Seen");
                default -> false;
            };
            if (match) results.add(i + 1);
        }

        StringBuilder sb = new StringBuilder("* SEARCH");
        results.forEach(n -> sb.append(" ").append(n));
        sendLine(sb.toString());
        sendLine(tag + " OK SEARCH completed");
    }

    private void handleLogout(String tag) {
        state = ImapState.LOGOUT;
        sendLine("* BYE IMAP Server logging out");
        sendLine(tag + " OK LOGOUT completed");
    }

    private void addFlag(EmailRecord record, String flag) {
        String current = record.getFlags();
        if (!current.contains(flag)) {
            String updated = (current.isEmpty() ? flag : current + " " + flag).trim();
            emailRepository.updateFlags(record.getId(), updated);
        }
    }

    private void removeFlag(EmailRecord record, String flag) {
        String current = record.getFlags();
        if (current.contains(flag)) {
            String updated = current.replace(flag, "").replaceAll("\\s+", " ").trim();
            emailRepository.updateFlags(record.getId(), updated);
        }
    }

    private void setFlags(EmailRecord record, List<String> flags) {
        emailRepository.updateFlags(record.getId(), String.join(" ", flags).trim());
    }

    private String parseHeaders(String fullContent) {
        int idx = fullContent.indexOf("\r\n\r\n");
        return (idx != -1) ? fullContent.substring(0, idx + 4) : fullContent;
    }

    private String parseBody(String fullContent) {
        int idx = fullContent.indexOf("\r\n\r\n");
        return (idx != -1) ? fullContent.substring(idx + 4) : "";
    }
}