package org.example;
import java.io.*;
import java.net.*;
import java.util.*;

import org.example.service.ServerObserver;

public class Pop3Server {
    private final int port;
    private final ServerObserver observer;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final List<Pop3Session> activeSessions = Collections.synchronizedList(new ArrayList<>());

    public Pop3Server(int port, ServerObserver observer) {
        this.port = port;
        this.observer = observer;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        if (observer != null) observer.logEvent("Serveur POP3 en écoute sur le port " + port);

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                String clientIp = clientSocket.getInetAddress().getHostAddress();
                if (observer != null) observer.logEvent("Nouvelle connexion client: " + clientIp);

                Pop3Session session = new Pop3Session(clientSocket, observer, clientIp, this);
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
            for (Pop3Session session : activeSessions) {
                session.interruptSession();
            }
            activeSessions.clear();
        }
    }
    
    public void removeSession(Pop3Session session) {
        activeSessions.remove(session);
    }

    public static void main(String[] args) {
        try {
            new Pop3Server(110, null).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class Pop3Session extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final ServerObserver observer;
    private final String clientIp;
    private final Pop3Server server;
    private String username;
    private File userDir;
    private List<File> emails;
    private boolean authenticated;
    private List<Boolean> deletionFlags;
    private volatile boolean interrupted = false;

    public Pop3Session(Socket socket) {
        this(socket, null, socket.getInetAddress().getHostAddress(), null);
    }
    
    public Pop3Session(Socket socket, ServerObserver observer, String clientIp, Pop3Server server) {
        this.socket = socket;
        this.observer = observer;
        this.clientIp = clientIp;
        this.server = server;
        this.authenticated = false;
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
            if (observer != null && observer instanceof org.example.service.Pop3ServerService) {
                ((org.example.service.Pop3ServerService) observer).incrementClient();
            }
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendResponse("+OK POP3 server ready");

            String line;
            while (!interrupted && (line = in.readLine()) != null) {
                if (observer != null) observer.logRequest(clientIp, line);
                String[] parts = line.split(" ", 2);
                String command = parts[0].toUpperCase();
                String argument = parts.length > 1 ? parts[1] : "";

                switch (command.toUpperCase()) {
                    case "USER":
                        handleUser(argument);
                        break;
                    case "PASS":
                        handlePass(argument);
                        break;
                    case "STAT":
                        handleStat();
                        break;
                    case "LIST":
                        handleList();
                        break;
                    case "RETR":
                        handleRetr(argument);
                        break;
                    case "DELE":
                        handleDele(argument);
                        break;
                    case "RSET":
                        handleRset();
                        break;
                    case "NOOP":
                        handleNoop();
                        break;
                    case "QUIT":
                        handleQuit();
                        return; // Terminer la session
                    default:
                        sendResponse("-ERR Unknown command");
                        break;
                }

            }
            // Si la boucle se termine, cela signifie que la connexion a été interrompue sans QUIT.
            if (authenticated) {
                if (observer != null) observer.logEvent("Connexion interrompue sans recevoir QUIT. Suppressions ignorées.");
            }
        } catch (IOException e) {
            if (!interrupted && observer != null) {
                observer.logEvent("Erreur session " + clientIp + " : " + e.getMessage());
            }
        } finally {
            try { socket.close(); } catch (IOException e) { /* Ignore */ }
            if (server != null) server.removeSession(this);
            if (observer != null) {
                observer.logEvent("Déconnexion client: " + clientIp);
                if (observer instanceof org.example.service.Pop3ServerService) {
                    ((org.example.service.Pop3ServerService) observer).decrementClient();
                }
            }
        }
    }

    private void handleUser(String arg) {
        File mailserverDir = new File("mailserver");
        if (!mailserverDir.exists()) {
            mailserverDir = new File("../mailserver");
        }
        
        File dir = new File(mailserverDir, arg);
        if (dir.exists() && dir.isDirectory()) {
            username = arg;
            userDir = dir;
            sendResponse("+OK User accepted");
        } else {
            sendResponse("-ERR User not found");
        }
    }

    private void handlePass(String arg) {
        if (username == null) {
            sendResponse("-ERR USER required first");
            return;
        }
        // Pour simplifier, on suppose que "userDir" est le dossier de l'utilisateur déjà défini
        authenticated = true;
        // Chargez les fichiers du répertoire dans une ArrayList mutable
        // Filter only .txt files (exclude .flags companion files used by IMAP)
        File[] files = userDir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null) {
            emails = new ArrayList<>();
        } else {
            emails = new ArrayList<>(Arrays.asList(files));
        }
        // Initialisez les flags de suppression : aucun email n'est marqué (false)
        deletionFlags = new ArrayList<>();
        for (int i = 0; i < emails.size(); i++) {
            deletionFlags.add(false);
        }
        sendResponse("+OK Password accepted");
    }

    private void handleStat() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        long size = emails.stream().mapToLong(File::length).sum();
        sendResponse("+OK " + emails.size() + " " + size);
    }

    private void handleList() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        sendResponse("+OK " + emails.size() + " messages");
        for (int i = 0; i < emails.size(); i++) {
            sendResponse((i + 1) + " " + emails.get(i).length());
        }
        sendResponse(".");
    }

    private void handleRetr(String arg) {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        try {
            int index = Integer.parseInt(arg) - 1;
            if (index < 0 || index >= emails.size()) {
                sendResponse("-ERR No such message");
                return;
            }
            File emailFile = emails.get(index);
            sendResponse("+OK " + emailFile.length() + " octets");
            BufferedReader reader = new BufferedReader(new FileReader(emailFile));
            String line;
            while ((line = reader.readLine()) != null) {
                // RFC 1939 Section 3 - Byte-stuffing:
                // If a line begins with a termination octet ("."),
                // prepend an additional "." to avoid premature termination.
                if (line.startsWith(".")) {
                    sendResponse("." + line);
                } else {
                    sendResponse(line);
                }
            }
            sendResponse(".");
            reader.close();
        } catch (Exception e) {
            sendResponse("-ERR Invalid message number");
        }
    }

    private void handleDele(String arg) {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        try {
            arg = arg.trim();
            int index = Integer.parseInt(arg) - 1; // Les messages sont numérotés à partir de 1
            if (index < 0 || index >= emails.size()) {
                sendResponse("-ERR No such message");
                return;
            }
            // Vérifier si le message est déjà marqué pour suppression
            if (deletionFlags.get(index)) {
                sendResponse("-ERR Message already marked for deletion");
                return;
            }
            // Marquer le message pour suppression (ne pas le supprimer tout de suite)
            deletionFlags.set(index, true);
            sendResponse("+OK Message marked for deletion");
        } catch (NumberFormatException nfe) {
            sendResponse("-ERR Invalid message number");
        } catch (Exception e) {
            sendResponse("-ERR Invalid message number");
        }
    }
    private void handleRset() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        // Remise à zéro de tous les flags de suppression
        for (int i = 0; i < deletionFlags.size(); i++) {
            deletionFlags.set(i, false);
        }
        sendResponse("+OK Deletion marks reset");
    }

    // RFC 1939 - NOOP command: do nothing, return +OK
    private void handleNoop() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }
        sendResponse("+OK");
    }

    private void handleQuit() {
        // Pour chaque email marqué pour suppression, supprimez le fichier
        for (int i = deletionFlags.size() - 1; i >= 0; i--) {
            if (deletionFlags.get(i)) {
                File emailFile = emails.get(i);
                if (emailFile.delete()) {
                    if (observer != null) observer.logEvent("Email supprimé: " + emailFile.getName());
                    // Optionnel : vous pouvez retirer l'email de la liste
                    emails.remove(i);
                    deletionFlags.remove(i);
                } else {
                    if (observer != null) observer.logEvent("Échec de suppression de l'email: " + emailFile.getName());
                }
            }
        }
        sendResponse("+OK POP3 server signing off");
    }

}
