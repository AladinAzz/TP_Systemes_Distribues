# Rapport d'Implémentation - Interfaces Graphiques d'Administration

Suite à votre demande, voici le rapport détaillé décrivant comment nous avons implémenté les interfaces graphiques pour les serveurs SMTP, POP3 et IMAP, de manière à respecter l'intégralité du cahier des charges (Sections 3.1, 3.2 et 3.3 de votre énoncé).

## 1. Principe Général d'Indépendance (Section 3.1)

Afin de permettre de déployer chaque serveur sur une machine différente dans un environnement distribué, nous n'avons pas créé une seule interface monolithique. Au lieu de cela, nous avons restructuré le projet en **trois applications distinctes** :

1.  **Module SMTP** (Application Spring Boot autonome)
2.  **Module POP3** (Application Spring Boot autonome)
3.  **Module IMAP** (Application Spring Boot autonome)

Chaque module s'exécute de manière totalement indépendante sur son propre serveur web intégré (Tomcat) :
- L'interface d'administration SMTP tourne sur le port **8080**.
- L'interface d'administration POP3 tourne sur le port **8081**.
- L'interface d'administration IMAP tourne sur le port **8082**.

Ce choix d'architecture (Framework Web en Spring Boot au lieu de composants fenêtrés "Swing") facilite énormément une future distribution : les administrateurs peuvent y accéder depuis n'importe quel navigateur sur le réseau sans devoir installer de client lourd.

## 2. Fonctionnalités Minimales Développées (Section 3.2)

Chaque interface web (accessible via `http://localhost:808x`) intègre un **panneau de contrôle** répondant aux exigences suivantes :

*   **Démarrer le serveur** : Un bouton "Démarrer Serveur" est présent. Lorsqu'il est cliqué, il déclenche un appel d'API REST vers le backend, qui instancie de manière propre et asynchrone le `ServerSocket` Java natif correspondant (sur le port métier : `2525` pour SMTP, `110` pour POP3, `143` pour IMAP).
*   **Arrêter le serveur** : Un bouton "Arrêter Serveur" arrête proprement le `ServerSocket` et libère les connexions TCP actives via une méthode ([stop()](file:///c:/Users/derrm/Documents/System%20distrubue/TP_Syst-mes_Distribu-s/IMAP/src/main/java/org/example/ImapServer.java#45-63)) qui interrompt proprement les threads clients.
*   **Historique des commandes (en temps réel)** : Chaque page web possède un module "Terminal" au centre. Les interactions entre le client Java "Sockets" et le serveur Java sont poussées **en direct** sur la page Web via la technologie **WebSockets** (STOMP / SockJS).
    *   Le format `Client -> HELO eoc.dz` et `Serveur -> 250 Hello` est strictement respecté.
    *   Afin d'améliorer la lisibilité (comme demandé pour "observer clairement les interactions"), une coloration syntaxique est appliquée : les actions du client sont en gris/blanc, celles du serveur en orange.

## 3. Fonctionnalités Recommandées (Bonus) Ajoutées (Section 3.3)

Nous avons également couvert l'intégralité des fonctionnalités bonus recommandées pour améliorer l'outil d'administration :

### Affichage du nombre de clients connectés
Chaque interface web comporte une tuile dynamique affichant le nombre de clients actuellement connectés. Le chiffre est mis à jour en direct via un canal WebSocket dédié, qui est incrémenté lors d'un `socket.accept()` et décrémenté lors la femeture d'un [ImapSession](file:///c:/Users/derrm/Documents/System%20distrubue/TP_Syst-mes_Distribu-s/IMAP/src/main/java/org/example/ImapServer.java#77-624) ou [Pop3Session](file:///c:/Users/derrm/Documents/System%20distrubue/TP_Syst-mes_Distribu-s/src/main/java/org/example/Pop3Server.java#27-261).

### Journalisation des événements (connexion, déconnexion, erreurs)
Le terminal en direct affiche également les processus de fond internes au système :
- Démarrage effectif du port (`INFO -> Serveur IMAP en écoute sur le port 143`).
- Notification de nouvelle connexion client avec récupération de l'adresse (`INFO -> Nouvelle connexion client: 127.0.0.1`).
- Notification de déconnexion (`INFO -> Déconnexion client: 127.0.0.1`).
- Rapport des erreurs (`socket closed`, exceptions, etc).
*Ces événements internes sont affichés de couleur bleue cyan sur l'interface.*

### Horodatage des commandes
Dans l'historique web, **chaque ligne** (Requête client, Réponse Serveur, ou Événement système) est préfixée par son horodatage. 
Exemple: `[11:45:30] Client (127.0.0.1) -> MAIL FROM:mohammed@eoc.dz`.

### Bonus Supplémentaire : Sauvegarde dans un fichier texte (Logs)
Pour garantir la traçabilité à long terme au-delà de la surveillance web, chaque module (Smtp, Pop3, Imap) génère un journal texte dans un dossier dédié (`SMTP/logs/smtp_server.log`, `POP3/logs/pop3_server.log`, etc.).
Dans ces fichiers, les logs incluent :
- La date complète *[YYYY-MM-DD HH:mm:ss]*
- Le nom d'utilisateur Windows *[Utilisateur]*
- Exemple brut : `[2026-03-12 11:45:30] [derrm] Client (127.0.0.1) -> CAPABILITY`

---

### Conclusion sur l'exécution
L'outil d'administration est validé et prêt à l'emploi. Le système distribué s'opère simplement : en lançant dans IntelliJ les classes respectives ([SmtpApplication](file:///c:/Users/derrm/Documents/System%20distrubue/TP_Syst-mes_Distribu-s/SMTP/src/main/java/org/example/SmtpApplication.java#6-13), [Pop3Application](file:///c:/Users/derrm/Documents/System%20distrubue/TP_Syst-mes_Distribu-s/POP3/src/main/java/org/example/Pop3Application.java#7-16), [ImapApplication](file:///c:/Users/derrm/Documents/System%20distrubue/TP_Syst-mes_Distribu-s/IMAP/src/main/java/org/example/ImapApplication.java#7-16)), il suffira d'ouvrir des fenêtres de navigateur sur les 3 différents ports pour piloter l'entièreté des communications.
