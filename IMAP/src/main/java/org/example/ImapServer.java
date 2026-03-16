package org.example;

import java.io.*;
import java.net.*;
import java.util.*;

import org.example.service.ServerObserver;

public class ImapServer {
    private final int port;
    private final ServerObserver observer;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final List<ImapSession> activeSessions = Collections.synchronizedList(new ArrayList<>());

    public ImapServer(int port, ServerObserver observer) {
        this.port = port;
        this.observer = observer;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        if (observer != null) observer.logEvent("Serveur IMAP en écoute sur le port " + port);

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                String clientIp = clientSocket.getInetAddress().getHostAddress();
                if (observer != null) observer.logEvent("Nouvelle connexion client: " + clientIp);

                ImapSession session = new ImapSession(clientSocket, observer, clientIp, this);
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
            for (ImapSession session : activeSessions) {
                session.interruptSession();
            }
            activeSessions.clear();
        }
    }
    
    public void removeSession(ImapSession session) {
        activeSessions.remove(session);
    }

    public static void main(String[] args) {
        try {
            new ImapServer(143, null).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class ImapSession extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final ServerObserver observer;
    private final String clientIp;
    private final ImapServer server;
    private volatile boolean interrupted = false;

    // Finite State Machine for IMAP session (RFC 9051)
    private enum ImapState {
        NOT_AUTHENTICATED,
        AUTHENTICATED,
        SELECTED,
        LOGOUT
    }

    private ImapState state;
    private String username;
    private File userDir;
    private List<File> emails;
    private String selectedMailbox;

    public ImapSession(Socket socket) {
        this(socket, null, socket.getInetAddress().getHostAddress(), null);
    }
    
    public ImapSession(Socket socket, ServerObserver observer, String clientIp, ImapServer server) {
        this.socket = socket;
        this.observer = observer;
        this.clientIp = clientIp;
        this.server = server;
        this.state = ImapState.NOT_AUTHENTICATED;
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
            if (observer != null && observer instanceof org.example.service.ImapServerService) {
                ((org.example.service.ImapServerService) observer).incrementClient();
            }
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // RFC 9051 - Server greeting
            sendResponse("* OK [CAPABILITY IMAP4rev2] IMAP4rev2 Service Ready");

            String line;
            while (!interrupted && (line = in.readLine()) != null) {
                if (observer != null) observer.logRequest(clientIp, line);

                // Parse tag and command
                String[] parts = line.split(" ", 3);
                if (parts.length < 2) {
                    sendResponse("* BAD Invalid command format");
                    continue;
                }

                String tag = parts[0];
                String command = parts[1].toUpperCase();
                String arguments = parts.length > 2 ? parts[2] : "";

                switch (command) {
                    case "CAPABILITY":
                        handleCapability(tag);
                        break;
                    case "NOOP":
                        handleNoop(tag);
                        break;
                    case "LOGIN":
                        handleLogin(tag, arguments);
                        break;
                    case "SELECT":
                        handleSelect(tag, arguments);
                        break;
                    case "FETCH":
                        handleFetch(tag, arguments);
                        break;
                    case "STORE":
                        handleStore(tag, arguments);
                        break;
                    case "SEARCH":
                        handleSearch(tag, arguments);
                        break;
                    case "LOGOUT":
                        handleLogout(tag);
                        return;
                    default:
                        sendResponse(tag + " BAD Unknown command");
                        break;
                }
            }
        } catch (IOException e) {
            if (!interrupted && observer != null) {
                observer.logEvent("Erreur session " + clientIp + " : " + e.getMessage());
            }
        } finally {
            try { socket.close(); } catch (IOException e) { /* ignore */ }
            if (server != null) server.removeSession(this);
            if (observer != null) {
                observer.logEvent("Déconnexion client: " + clientIp);
                if (observer instanceof org.example.service.ImapServerService) {
                    ((org.example.service.ImapServerService) observer).decrementClient();
                }
            }
        }
    }

    // ===================== Command Handlers =====================

    private void handleCapability(String tag) {
        sendResponse("* CAPABILITY IMAP4rev2");
        sendResponse(tag + " OK CAPABILITY completed");
    }

    private void handleNoop(String tag) {
        sendResponse(tag + " OK NOOP completed");
    }

    private void handleLogin(String tag, String args) {
        if (state != ImapState.NOT_AUTHENTICATED) {
            sendResponse(tag + " BAD Already authenticated");
            return;
        }

        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            sendResponse(tag + " BAD LOGIN requires username and password");
            return;
        }

        String user = parts[0].replaceAll("\"", "");
        // Password is accepted for simplicity (same as POP3)

        File mailserverDir = new File("mailserver");
        if (!mailserverDir.exists()) {
            mailserverDir = new File("../mailserver");
        }

        File dir = new File(mailserverDir, user);
        if (dir.exists() && dir.isDirectory()) {
            username = user;
            userDir = dir;
            state = ImapState.AUTHENTICATED;
            sendResponse(tag + " OK LOGIN completed");
        } else {
            sendResponse(tag + " NO [AUTHENTICATIONFAILED] LOGIN failed");
        }
    }

    private void handleSelect(String tag, String args) {
        if (state == ImapState.NOT_AUTHENTICATED) {
            sendResponse(tag + " BAD Command requires authentication");
            return;
        }

        String mailbox = args.trim().replaceAll("\"", "");

        // Only INBOX is supported (as per assignment spec)
        if (!mailbox.equalsIgnoreCase("INBOX")) {
            sendResponse(tag + " NO [NONEXISTENT] Mailbox does not exist");
            return;
        }

        // Load emails (only .txt files, exclude .flags companion files)
        File[] files = userDir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null) {
            emails = new ArrayList<>();
        } else {
            Arrays.sort(files);
            emails = new ArrayList<>(Arrays.asList(files));
        }

        selectedMailbox = mailbox;
        state = ImapState.SELECTED;

        // Count unseen messages
        int unseen = 0;
        int firstUnseen = -1;
        for (int i = 0; i < emails.size(); i++) {
            if (!getFlags(emails.get(i)).contains("\\Seen")) {
                unseen++;
                if (firstUnseen == -1) firstUnseen = i + 1;
            }
        }

        // RFC 9051 - SELECT response
        sendResponse("* " + emails.size() + " EXISTS");
        sendResponse("* 0 RECENT");
        sendResponse("* FLAGS (\\Seen \\Answered \\Flagged \\Deleted \\Draft)");
        sendResponse("* OK [PERMANENTFLAGS (\\Seen \\Deleted)]");
        if (firstUnseen > 0) {
            sendResponse("* OK [UNSEEN " + firstUnseen + "]");
        }
        sendResponse(tag + " OK [READ-WRITE] SELECT completed");
    }

    private void handleFetch(String tag, String args) {
        if (state != ImapState.SELECTED) {
            if (state == ImapState.NOT_AUTHENTICATED) {
                sendResponse(tag + " BAD Command requires authentication");
            } else {
                sendResponse(tag + " BAD No mailbox selected");
            }
            return;
        }

        // Parse: <sequence> <data items>
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            sendResponse(tag + " BAD FETCH requires sequence number and data items");
            return;
        }

        int seqNum;
        try {
            seqNum = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendResponse(tag + " BAD Invalid sequence number");
            return;
        }

        if (seqNum < 1 || seqNum > emails.size()) {
            sendResponse(tag + " NO No such message");
            return;
        }

        File emailFile = emails.get(seqNum - 1);
        String dataItems = parts[1].trim();

        // Remove surrounding parentheses
        if (dataItems.startsWith("(") && dataItems.endsWith(")")) {
            dataItems = dataItems.substring(1, dataItems.length() - 1);
        }

        String upperItems = dataItems.toUpperCase();

        if (upperItems.equals("FLAGS")) {
            // Return flags only
            List<String> flags = getFlags(emailFile);
            sendResponse("* " + seqNum + " FETCH (FLAGS (" + String.join(" ", flags) + "))");

        } else if (upperItems.equals("BODY[HEADER]") || upperItems.equals("BODY.PEEK[HEADER]")) {
            // Return headers only
            String headers = getHeaders(emailFile);
            byte[] headerBytes = headers.getBytes();
            sendResponse("* " + seqNum + " FETCH (BODY[HEADER] {" + headerBytes.length + "}");
            sendResponse(headers + ")"); // Simplifié pour utiliser sendResponse au lieu de out.print & out.println

        } else if (upperItems.equals("BODY[]") || upperItems.equals("RFC822")
                || upperItems.equals("BODY.PEEK[]")) {
            // Return full message
            String content = getFullContent(emailFile);
            byte[] contentBytes = content.getBytes();
            sendResponse("* " + seqNum + " FETCH (BODY[] {" + contentBytes.length + "}");
            sendResponse(content + ")");
            // Auto-set \Seen flag if not using PEEK
            if (!upperItems.contains("PEEK")) {
                addFlag(emailFile, "\\Seen");
            }

        } else if (upperItems.equals("BODY[TEXT]") || upperItems.equals("BODY.PEEK[TEXT]")) {
            // Return body only
            String body = getBody(emailFile);
            byte[] bodyBytes = body.getBytes();
            sendResponse("* " + seqNum + " FETCH (BODY[TEXT] {" + bodyBytes.length + "}");
            sendResponse(body + ")");

        } else {
            sendResponse(tag + " BAD Unknown FETCH data item: " + dataItems);
            return;
        }

        sendResponse(tag + " OK FETCH completed");
    }

    private void handleStore(String tag, String args) {
        if (state != ImapState.SELECTED) {
            if (state == ImapState.NOT_AUTHENTICATED) {
                sendResponse(tag + " BAD Command requires authentication");
            } else {
                sendResponse(tag + " BAD No mailbox selected");
            }
            return;
        }

        // Parse: <sequence> <action> <flags>
        // e.g., "1 +FLAGS (\Seen)", "1 -FLAGS (\Seen)", "1 FLAGS (\Seen)"
        String[] parts = args.split(" ", 3);
        if (parts.length < 3) {
            sendResponse(tag + " BAD STORE requires sequence, action, and flags");
            return;
        }

        int seqNum;
        try {
            seqNum = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendResponse(tag + " BAD Invalid sequence number");
            return;
        }

        if (seqNum < 1 || seqNum > emails.size()) {
            sendResponse(tag + " NO No such message");
            return;
        }

        String action = parts[1].toUpperCase();
        String flagStr = parts[2].trim();

        // Extract flags from parentheses
        if (flagStr.startsWith("(") && flagStr.endsWith(")")) {
            flagStr = flagStr.substring(1, flagStr.length() - 1);
        }
        List<String> newFlags = Arrays.asList(flagStr.split("\\s+"));

        File emailFile = emails.get(seqNum - 1);

        switch (action) {
            case "+FLAGS":
            case "+FLAGS.SILENT":
                for (String flag : newFlags) {
                    addFlag(emailFile, flag);
                }
                break;
            case "-FLAGS":
            case "-FLAGS.SILENT":
                for (String flag : newFlags) {
                    removeFlag(emailFile, flag);
                }
                break;
            case "FLAGS":
            case "FLAGS.SILENT":
                // Ne plus forcer une List immuable pour que remove/add fonctionne après
                setFlags(emailFile, new ArrayList<>(newFlags));
                break;
            default:
                sendResponse(tag + " BAD Unknown STORE action");
                return;
        }

        // Send updated flags back (unless SILENT)
        if (!action.contains("SILENT")) {
            List<String> currentFlags = getFlags(emailFile);
            sendResponse("* " + seqNum + " FETCH (FLAGS (" + String.join(" ", currentFlags) + "))");
        }

        sendResponse(tag + " OK STORE completed");
    }

    private void handleSearch(String tag, String args) {
        if (state != ImapState.SELECTED) {
            if (state == ImapState.NOT_AUTHENTICATED) {
                sendResponse(tag + " BAD Command requires authentication");
            } else {
                sendResponse(tag + " BAD No mailbox selected");
            }
            return;
        }

        String[] parts = args.split(" ", 2);
        String criteria = parts[0].toUpperCase();
        String value = parts.length > 1 ? parts[1].replaceAll("\"", "") : "";

        List<Integer> results = new ArrayList<>();

        for (int i = 0; i < emails.size(); i++) {
            File emailFile = emails.get(i);

            switch (criteria) {
                case "ALL":
                    results.add(i + 1);
                    break;
                case "FROM":
                    if (headerContains(emailFile, "From:", value)) {
                        results.add(i + 1);
                    }
                    break;
                case "SUBJECT":
                    if (headerContains(emailFile, "Subject:", value)) {
                        results.add(i + 1);
                    }
                    break;
                case "UNSEEN":
                    if (!getFlags(emailFile).contains("\\Seen")) {
                        results.add(i + 1);
                    }
                    break;
                case "SEEN":
                    if (getFlags(emailFile).contains("\\Seen")) {
                        results.add(i + 1);
                    }
                    break;
                default:
                    sendResponse(tag + " BAD Unknown search criteria");
                    return;
            }
        }

        StringBuilder sb = new StringBuilder("* SEARCH");
        for (int seq : results) {
            sb.append(" ").append(seq);
        }
        sendResponse(sb.toString());
        sendResponse(tag + " OK SEARCH completed");
    }

    private void handleLogout(String tag) {
        state = ImapState.LOGOUT;
        sendResponse("* BYE IMAP4rev2 Server logging out");
        sendResponse(tag + " OK LOGOUT completed");
    }

    // ===================== Flag Helpers =====================

    private List<String> getFlags(File emailFile) {
        File flagFile = new File(emailFile.getAbsolutePath() + ".flags");
        List<String> flags = new ArrayList<>();
        if (flagFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(flagFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        flags.add(line);
                    }
                }
            } catch (IOException e) {
                if (observer != null) observer.logEvent("Erreur lecture flags: " + e.getMessage());
            }
        }
        return flags;
    }

    private void addFlag(File emailFile, String flag) {
        List<String> flags = getFlags(emailFile);
        if (!flags.contains(flag)) {
            flags.add(flag);
            writeFlags(emailFile, flags);
        }
    }

    private void removeFlag(File emailFile, String flag) {
        List<String> flags = getFlags(emailFile);
        flags.remove(flag);
        writeFlags(emailFile, flags);
    }

    private void setFlags(File emailFile, List<String> flags) {
        writeFlags(emailFile, flags);
    }

    private void writeFlags(File emailFile, List<String> flags) {
        File flagFile = new File(emailFile.getAbsolutePath() + ".flags");
        try (PrintWriter writer = new PrintWriter(new FileWriter(flagFile))) {
            for (String flag : flags) {
                writer.println(flag);
            }
        } catch (IOException e) {
            if (observer != null) observer.logEvent("Erreur écriture flags: " + e.getMessage());
        }
    }

    // ===================== Email Content Helpers =====================

    private String getHeaders(File emailFile) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(emailFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) break; // Headers end at first blank line
                sb.append(line).append("\r\n");
            }
            sb.append("\r\n"); // Blank line terminates headers
        } catch (IOException e) {
            if (observer != null) observer.logEvent("Erreur lecture headers: " + e.getMessage());
        }
        return sb.toString();
    }

    private String getBody(File emailFile) {
        StringBuilder sb = new StringBuilder();
        boolean inBody = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(emailFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (inBody) {
                    sb.append(line).append("\r\n");
                } else if (line.trim().isEmpty()) {
                    inBody = true;
                }
            }
        } catch (IOException e) {
             if (observer != null) observer.logEvent("Erreur lecture body: " + e.getMessage());
        }
        return sb.toString();
    }

    private String getFullContent(File emailFile) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(emailFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\r\n");
            }
        } catch (IOException e) {
            if (observer != null) observer.logEvent("Erreur lecture contenu texte: " + e.getMessage());
        }
        return sb.toString();
    }

    private boolean headerContains(File emailFile, String headerName, String value) {
        try (BufferedReader reader = new BufferedReader(new FileReader(emailFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) break; // Only search in headers
                if (line.toLowerCase().startsWith(headerName.toLowerCase())) {
                    if (line.toLowerCase().contains(value.toLowerCase())) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            if (observer != null) observer.logEvent("Erreur recherche headers: " + e.getMessage());
        }
        return false;
    }
}
