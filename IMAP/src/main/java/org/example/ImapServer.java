package org.example;

import java.io.*;
import java.net.*;
import java.rmi.Naming;
import java.util.*;

import org.example.rmi.IAuthService;
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
        if (observer != null)
            observer.logEvent("Serveur IMAP en écoute sur le port " + port);

        while (running) {
            try {
                Socket client = serverSocket.accept();
                String ip = client.getInetAddress().getHostAddress();
                if (observer != null)
                    observer.logEvent("Nouvelle connexion client: " + ip);
                ImapSession session = new ImapSession(client, observer, ip, this);
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

    // FIX 9 — cached RMI stub, looked up once per session
    private IAuthService authService;

    private final Socket socket;
    private BufferedReader in;
    // FIX 6 — use a raw OutputStream so we can write literal bytes correctly
    private OutputStream rawOut;
    private PrintWriter out;
    private final ServerObserver observer;
    private final String clientIp;
    private final ImapServer server;
    private volatile boolean interrupted = false;

    private enum ImapState {
        NOT_AUTHENTICATED, AUTHENTICATED, SELECTED, LOGOUT
    }

    private ImapState state;
    private String username;
    private File userDir;
    private List<File> emails;
    private String selectedMailbox;

    public ImapSession(Socket socket) {
        this(socket, null, socket.getInetAddress().getHostAddress(), null);
    }

    public ImapSession(Socket socket, ServerObserver observer,
            String clientIp, ImapServer server) {
        this.socket = socket;
        this.observer = observer;
        this.clientIp = clientIp;
        this.server = server;
        this.state = ImapState.NOT_AUTHENTICATED;
    }

    // FIX 9 — single RMI lookup per session
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

    private void sendLine(String line) {
        if (interrupted)
            return;
        try {
            rawOut.write((line + "\r\n").getBytes("UTF-8"));
            rawOut.flush();
        } catch (IOException e) {
            interrupted = true;
        }
        if (observer != null)
            observer.logResponse(line);
    }

    /**
     * FIX 6 — Sends an IMAP literal block with correct framing:
     *
     * * seqNum FETCH (BODY[xxx] {N}\r\n
     * <N bytes of content>\r\n
     * )\r\n
     * tag OK FETCH completed\r\n
     *
     * Using sendResponse() for both the prefix and the content was wrong because
     * PrintWriter.println() appends a platform newline AFTER the content, making
     * the byte count in {N} incorrect and placing ")" on the wrong line.
     */
    private void sendLiteral(String tag, int seqNum, String fetchItem,
            String content, String flagsIfAny) throws IOException {
        if (interrupted)
            return;
        byte[] contentBytes = content.getBytes("UTF-8");
        String prefix = "* " + seqNum + " FETCH (" + fetchItem +
                " {" + contentBytes.length + "}";
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
            if (observer instanceof org.example.service.ImapServerService)
                ((org.example.service.ImapServerService) observer).incrementClient();

            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            rawOut = socket.getOutputStream();
            out = new PrintWriter(rawOut, true);

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
                        sendLine(tag + " BAD Unknown command");
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
                if (observer instanceof org.example.service.ImapServerService)
                    ((org.example.service.ImapServerService) observer).decrementClient();
            }
        }
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private void handleCapability(String tag) {
        sendLine("* CAPABILITY IMAP4rev2");
        sendLine(tag + " OK CAPABILITY completed");
    }

    private void handleNoop(String tag) {
        sendLine(tag + " OK NOOP completed");
    }

    private void handleLogin(String tag, String args) {
        if (state != ImapState.NOT_AUTHENTICATED) {
            sendLine(tag + " BAD Already authenticated");
            return;
        }
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            sendLine(tag + " BAD LOGIN requires username and password");
            return;
        }

        String user = parts[0].replaceAll("\"", "");
        String pass = parts[1].replaceAll("\"", "");

        // FIX 9 — use cached RMI stub
        IAuthService svc = getAuthService();
        if (svc == null) {
            sendLine(tag + " BAD Internal server error (RMI)");
            return;
        }

        try {
            String token = svc.authenticate(user, pass);
            if (token == null) {
                sendLine(tag + " NO [AUTHENTICATIONFAILED] LOGIN failed");
                if (observer != null)
                    observer.logEvent("Échec auth pour '" + user + "'");
                return;
            }
            if (observer != null)
                observer.logEvent("Auth réussie pour '" + user +
                        "' (JWT: " + token.substring(0, Math.min(10, token.length())) + "...)");
        } catch (Exception e) {
            if (observer != null)
                observer.logEvent("Erreur RMI (LOGIN): " + e.getMessage());
            sendLine(tag + " BAD Internal server error (RMI)");
            return;
        }

        File mailserverDir = resolveMailserverDir();
        File dir = new File(mailserverDir, user);
        if (!dir.exists())
            dir.mkdirs();

        username = user;
        userDir = dir;
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

        File[] files = userDir.listFiles((d, name) -> name.endsWith(".txt"));
        emails = files != null
                ? new ArrayList<>(Arrays.asList(files))
                : new ArrayList<>();
        Collections.sort(emails);

        selectedMailbox = mailbox;
        state = ImapState.SELECTED;

        int unseen = 0, firstUnseen = -1;
        for (int i = 0; i < emails.size(); i++) {
            if (!getFlags(emails.get(i)).contains("\\Seen")) {
                unseen++;
                if (firstUnseen == -1)
                    firstUnseen = i + 1;
            }
        }

        sendLine("* " + emails.size() + " EXISTS");
        sendLine("* 0 RECENT");
        sendLine("* FLAGS (\\Seen \\Answered \\Flagged \\Deleted \\Draft)");
        sendLine("* OK [PERMANENTFLAGS (\\Seen \\Deleted)]");
        if (firstUnseen > 0)
            sendLine("* OK [UNSEEN " + firstUnseen + "]");
        sendLine(tag + " OK [READ-WRITE] SELECT completed");
    }

    /**
     * FIX 6 — All literal-bearing FETCH responses now go through sendLiteral()
     * which writes the exact byte count and correct framing.
     */
    private void handleFetch(String tag, String args) {
        if (state != ImapState.SELECTED) {
            sendLine(tag + (state == ImapState.NOT_AUTHENTICATED
                    ? " BAD Command requires authentication"
                    : " BAD No mailbox selected"));
            return;
        }
        String[] parts = args.split(" ", 2);
        if (parts.length < 2) {
            sendLine(tag + " BAD FETCH requires sequence and data items");
            return;
        }

        int seqNum;
        try {
            seqNum = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendLine(tag + " BAD Invalid sequence number");
            return;
        }

        if (seqNum < 1 || seqNum > emails.size()) {
            sendLine(tag + " NO No such message");
            return;
        }

        File emailFile = emails.get(seqNum - 1);
        String dataItems = parts[1].trim();
        if (dataItems.startsWith("(") && dataItems.endsWith(")"))
            dataItems = dataItems.substring(1, dataItems.length() - 1);

        String upper = dataItems.toUpperCase();

        try {
            if (upper.equals("FLAGS")) {
                List<String> flags = getFlags(emailFile);
                sendLine("* " + seqNum + " FETCH (FLAGS (" + String.join(" ", flags) + "))");
                sendLine(tag + " OK FETCH completed");

            } else if (upper.equals("BODY[HEADER]") || upper.equals("BODY.PEEK[HEADER]")) {
                String headers = getHeaders(emailFile);
                sendLiteral(tag, seqNum, "BODY[HEADER]", headers, null);

            } else if (upper.equals("BODY[]") || upper.equals("RFC822") || upper.equals("BODY.PEEK[]")) {
                String content = getFullContent(emailFile);
                if (!upper.contains("PEEK"))
                    addFlag(emailFile, "\\Seen");
                sendLiteral(tag, seqNum, "BODY[]", content, null);

            } else if (upper.equals("BODY[TEXT]") || upper.equals("BODY.PEEK[TEXT]")) {
                String body = getBody(emailFile);
                sendLiteral(tag, seqNum, "BODY[TEXT]", body, null);

            } else {
                sendLine(tag + " BAD Unknown FETCH data item: " + dataItems);
            }
        } catch (IOException e) {
            sendLine(tag + " NO Internal error reading message");
        }
    }

    private void handleStore(String tag, String args) {
        if (state != ImapState.SELECTED) {
            sendLine(tag + (state == ImapState.NOT_AUTHENTICATED
                    ? " BAD Command requires authentication"
                    : " BAD No mailbox selected"));
            return;
        }
        String[] parts = args.split(" ", 3);
        if (parts.length < 3) {
            sendLine(tag + " BAD STORE requires sequence, action, and flags");
            return;
        }

        int seqNum;
        try {
            seqNum = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendLine(tag + " BAD Invalid sequence number");
            return;
        }

        if (seqNum < 1 || seqNum > emails.size()) {
            sendLine(tag + " NO No such message");
            return;
        }

        String action = parts[1].toUpperCase();
        String flagStr = parts[2].trim();
        if (flagStr.startsWith("(") && flagStr.endsWith(")"))
            flagStr = flagStr.substring(1, flagStr.length() - 1);
        List<String> newFlags = new ArrayList<>(Arrays.asList(flagStr.split("\\s+")));

        File emailFile = emails.get(seqNum - 1);
        switch (action) {
            case "+FLAGS":
            case "+FLAGS.SILENT":
                newFlags.forEach(f -> addFlag(emailFile, f));
                break;
            case "-FLAGS":
            case "-FLAGS.SILENT":
                newFlags.forEach(f -> removeFlag(emailFile, f));
                break;
            case "FLAGS":
            case "FLAGS.SILENT":
                setFlags(emailFile, newFlags);
                break;
            default:
                sendLine(tag + " BAD Unknown STORE action");
                return;
        }

        if (!action.contains("SILENT")) {
            List<String> current = getFlags(emailFile);
            sendLine("* " + seqNum + " FETCH (FLAGS (" + String.join(" ", current) + "))");
        }
        sendLine(tag + " OK STORE completed");
    }

    private void handleSearch(String tag, String args) {
        if (state != ImapState.SELECTED) {
            sendLine(tag + (state == ImapState.NOT_AUTHENTICATED
                    ? " BAD Command requires authentication"
                    : " BAD No mailbox selected"));
            return;
        }
        String[] parts = args.split(" ", 2);
        String criteria = parts[0].toUpperCase();
        String value = parts.length > 1 ? parts[1].replaceAll("\"", "") : "";

        List<Integer> results = new ArrayList<>();
        for (int i = 0; i < emails.size(); i++) {
            File f = emails.get(i);
            boolean match = false;
            switch (criteria) {
                case "ALL":
                    match = true;
                    break;
                case "FROM":
                    match = headerContains(f, "From:", value);
                    break;
                case "SUBJECT":
                    match = headerContains(f, "Subject:", value);
                    break;
                case "UNSEEN":
                    match = !getFlags(f).contains("\\Seen");
                    break;
                case "SEEN":
                    match = getFlags(f).contains("\\Seen");
                    break;
                default:
                    sendLine(tag + " BAD Unknown search criteria");
                    return;
            }
            if (match)
                results.add(i + 1);
        }

        StringBuilder sb = new StringBuilder("* SEARCH");
        results.forEach(n -> sb.append(" ").append(n));
        sendLine(sb.toString());
        sendLine(tag + " OK SEARCH completed");
    }

    private void handleLogout(String tag) {
        state = ImapState.LOGOUT;
        sendLine("* BYE IMAP4rev2 Server logging out");
        sendLine(tag + " OK LOGOUT completed");
    }

    // ── Flag helpers ──────────────────────────────────────────────────────────

    private List<String> getFlags(File emailFile) {
        File flagFile = new File(emailFile.getAbsolutePath() + ".flags");
        List<String> flags = new ArrayList<>();
        if (!flagFile.exists())
            return flags;
        try (BufferedReader r = new BufferedReader(new FileReader(flagFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty())
                    flags.add(line);
            }
        } catch (IOException e) {
            if (observer != null)
                observer.logEvent("Erreur lecture flags: " + e.getMessage());
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
        try (PrintWriter w = new PrintWriter(new FileWriter(flagFile))) {
            flags.forEach(w::println);
        } catch (IOException e) {
            if (observer != null)
                observer.logEvent("Erreur écriture flags: " + e.getMessage());
        }
    }

    // ── Content helpers ───────────────────────────────────────────────────────

    private String getHeaders(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty())
                    break;
                sb.append(line).append("\r\n");
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private String getBody(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean inBody = false;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (inBody)
                    sb.append(line).append("\r\n");
                else if (line.trim().isEmpty())
                    inBody = true;
            }
        }
        return sb.toString();
    }

    private String getFullContent(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null)
                sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    private boolean headerContains(File f, String headerName, String value) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty())
                    break;
                if (line.toLowerCase().startsWith(headerName.toLowerCase()) &&
                        line.toLowerCase().contains(value.toLowerCase()))
                    return true;
            }
        } catch (IOException e) {
            if (observer != null)
                observer.logEvent("Erreur recherche headers: " + e.getMessage());
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private File resolveMailserverDir() {
        File d = new File("mailserver");
        if (!d.exists())
            d = new File("../mailserver");
        return d;
    }
}