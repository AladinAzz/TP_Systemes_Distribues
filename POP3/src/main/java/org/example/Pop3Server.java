package org.example;

import java.io.*;
import java.net.*;

import java.util.*;

import org.example.client.AuthRestClient;
import org.example.db.EmailRecord;
import org.example.db.EmailRepository;
import org.example.service.ServerObserver;

public class Pop3Server {
    private final int port;
    private final ServerObserver observer;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final List<Pop3Session> activeSessions = Collections.synchronizedList(new ArrayList<>());
    private AuthRestClient authClientOverride;

    public Pop3Server(int port, ServerObserver observer) {
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
            observer.logEvent("Serveur POP3 en écoute sur le port " + port);

        while (running) {
            try {
                Socket client = serverSocket.accept();
                String ip = client.getInetAddress().getHostAddress();
                if (observer != null)
                    observer.logEvent("Nouvelle connexion client: " + ip);
                Pop3Session session = new Pop3Session(client, observer, ip, this);
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
            activeSessions.forEach(Pop3Session::interruptSession);
            activeSessions.clear();
        }
    }

    public void removeSession(Pop3Session s) {
        activeSessions.remove(s);
    }

    public static void main(String[] args) {
        try {
            new Pop3Server(110, null).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

class Pop3Session extends Thread {

    // FIX 9 — cache the RMI stub per session
    private AuthRestClient authClient;
    private final EmailRepository emailRepository = new EmailRepository();

    private final Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final ServerObserver observer;
    private final String clientIp;
    private final Pop3Server server;
    private volatile boolean interrupted = false;

    private String username;
    private List<EmailRecord> emails;
    private boolean authenticated;

    /**
     * FIX 8 — deletion flags are now tracked as a separate list of indices
     * so removal in QUIT is safe even after multiple DELEs.
     */
    private Set<Integer> markedForDeletion;

    public Pop3Session(Socket socket) {
        this(socket, null, socket.getInetAddress().getHostAddress(), null);
    }

    public Pop3Session(Socket socket, ServerObserver observer,
            String clientIp, Pop3Server server) {
        this.socket = socket;
        this.observer = observer;
        this.clientIp = clientIp;
        this.server = server;
        this.authenticated = false;
    }

    // FIX 9 — single lookup per session
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
            if (observer instanceof org.example.service.Pop3ServerService)
                ((org.example.service.Pop3ServerService) observer).incrementClient();

            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            sendResponse("+OK POP3 server ready");

            String line;
            while (!interrupted && (line = in.readLine()) != null) {
                if (observer != null)
                    observer.logRequest(clientIp, line);
                String[] parts = line.split(" ", 2);
                String command = parts[0].toUpperCase();
                String arg = parts.length > 1 ? parts[1] : "";

                switch (command) {
                    case "USER":
                        handleUser(arg);
                        break;
                    case "PASS":
                        handlePass(arg);
                        break;
                    case "STAT":
                        handleStat();
                        break;
                    case "LIST":
                        handleList();
                        break;
                    case "RETR":
                        handleRetr(arg);
                        break;
                    case "DELE":
                        handleDele(arg);
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
                        sendResponse("-ERR Unknown command");
                }
            }
            if (authenticated && observer != null)
                observer.logEvent("Connexion interrompue sans QUIT — suppressions ignorées.");
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
                if (observer instanceof org.example.service.Pop3ServerService)
                    ((org.example.service.Pop3ServerService) observer).decrementClient();
            }
        }
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private void handleUser(String arg) {
        username = arg;
        sendResponse("+OK User accepted, waiting for password");
    }

    private void handlePass(String arg) {
        if (username == null) {
            sendResponse("-ERR USER required first");
            return;
        }

        AuthRestClient svc = getAuthService();
        if (svc == null) {
            sendResponse("-ERR Internal server error (Auth unavailable)");
            return;
        }

        try {
            String token = svc.authenticate(username, arg);
            if (token == null) {
                sendResponse("-ERR Invalid password or user not found");
                if (observer != null)
                    observer.logEvent("Échec auth pour '" + username + "'");
                return;
            }
            if (observer != null)
                observer.logEvent("Auth REST réussie pour '" + username + "'");
        } catch (Exception e) {
            if (observer != null)
                observer.logEvent("Erreur Auth (PASS): " + e.getMessage());
            sendResponse("-ERR Internal server error");
            return;
        }

        emails = emailRepository.fetchEmails(username);
        markedForDeletion = new HashSet<>();
        authenticated = true;
        sendResponse("+OK Password accepted");
    }

    private void handleStat() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        long size = emails.stream().mapToLong(r -> r.getBody().length()).sum();
        sendResponse("+OK " + emails.size() + " " + size);
    }

    private void handleList() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        sendResponse("+OK " + emails.size() + " messages");
        for (int i = 0; i < emails.size(); i++) {
            sendResponse((i + 1) + " " + emails.get(i).getBody().length());
        }
        sendResponse(".");
    }

    private void handleRetr(String arg) {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        try {
            int index = Integer.parseInt(arg.trim()) - 1;
            EmailRecord email = emails.get(index);
            String content = email.getBody();
            sendResponse("+OK " + content.length() + " octets");
            
            for (String line : content.split("\r\n")) {
                if (line.startsWith("."))
                    sendResponse("." + line);
                else
                    sendResponse(line);
            }
            sendResponse(".");
        } catch (NumberFormatException e) {
            sendResponse("-ERR Invalid message number");
        }
    }

    private void handleDele(String arg) {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        try {
            int index = Integer.parseInt(arg.trim()) - 1;
            if (index < 0 || index >= emails.size()) {
                sendResponse("-ERR No such message");
                return;
            }
            // FIX 8 — mark index in a Set (idempotent, no index drift)
            if (markedForDeletion.contains(index)) {
                sendResponse("-ERR Message already marked for deletion");
                return;
            }
            markedForDeletion.add(index);
            sendResponse("+OK Message marked for deletion");
        } catch (NumberFormatException e) {
            sendResponse("-ERR Invalid message number");
        }
    }

    private void handleRset() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        markedForDeletion.clear();
        sendResponse("+OK Deletion marks reset");
    }

    private void handleNoop() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        sendResponse("+OK");
    }

    private void handleQuit() {
        // FIX 8 — iterate over the Set of marked indices in reverse order.
        // Sorting descending ensures removing index i doesn't invalidate
        // any lower index still to be removed.
        if (authenticated && markedForDeletion != null) {
            List<Integer> sorted = new ArrayList<>(markedForDeletion);
            sorted.sort(Collections.reverseOrder());
            for (int idx : sorted) {
                EmailRecord record = emails.get(idx);
                if (emailRepository.deleteEmail(record.getId())) {
                    if (observer != null)
                        observer.logEvent("Email supprimé en base: " + record.getId());
                    emails.remove(idx);
                } else {
                    if (observer != null)
                        observer.logEvent("Échec suppression en base: " + record.getId());
                }
            }
        }
        sendResponse("+OK POP3 server signing off");
    }

}
