# Rapport d'Implémentation : Serveur d'Authentification Distribué (Java RMI & JWT)

Ce rapport détaille la conception, l'architecture et l'implémentation du service d'authentification centralisé pour le système de messagerie distribué (SMTP, POP3, IMAP). Ce service utilise **Java RMI** pour la communication réseau et les **JSON Web Tokens (JWT)** pour générer des jetons d'accès sécurisés.

## 1. Architecture du Système

Le système d'authentification a été conçu autour de trois modules principaux, garantissant une séparation claire des responsabilités :
- **Auth-API** : Contient l'interface distante (`AuthService`) qui définit les contrats de communication.
- **Auth-Server** : Le serveur RMI responsable de la logique métier, de la gestion des utilisateurs, et de la génération des tokens JWT.
- **Auth-Client** : Une application cliente avec interface graphique permettant l'administration centralisée des comptes.

---

## 2. Implémentation du Serveur RMI (`Auth-Server`)

Le serveur central (`AuthServerMain`) écoute sur le port standard RMI **1099** et expose l'implémentation de `AuthServiceImpl`. La logique métier est scindée en deux gestionnaires distincts :

### 2.1. Gestion de la Persistance (`UserManager`)
- **Stockage** : Les comptes utilisateurs sont stockés de manière persistante dans un fichier JSON partagé (`mailserver/users.json`).
- **Technologie** : La bibliothèque Google Gson est utilisée pour la sérialisation et désérialisation du modèle de données (classe `User`).
- **Synchronisation** : Pour garantir l'intégrité des données face à des accès concurrents depuis de multiples clients (serveurs IMAP, POP3, SMTP et l'interface d'administration), toutes les méthodes manipulant les données (lecture, création, modification et suppression) intègrent le mot-clé `synchronized`.

### 2.2. Gestion de la Sécurité HTTP/Session (`JwtManager`)
- **Génération JWT** : Lorsqu'un utilisateur s'authentifie avec succès via ses identifiants (`authenticate`), un **JWT** signé est généré. Ce jeton permet ensuite aux services de valider les sessions sans re-demander les mots de passe de façon continue.
- **Algorithme & Clé** : L'algorithme **HS256** (HMAC-SHA256) est instancié avec une clé forte générée cryptographiquement au lancement du service (`Keys.secretKeyFor`).
- **Validité** : Chaque token généré incorpore une date d'expiration fixée à **2 heures** (`EXPIRATION_TIME`), limitant ainsi la durée de vie des sessions.

---

## 3. Interface d'Administration (`Auth-Client`)

Le client RMI (`AuthClientMain`) fournit une Interface Homme-Machine (IHM) développée avec **Java Swing**.
- **Fonctionnalités** : Elle permet aux administrateurs de lister visuellement les comptes inscrits, d'en ajouter de nouveaux, de modifier les mots de passe et de supprimer des comptes si besoin.
- **Communication RPC** : L'IHM récupère la référence du service distant via `Naming.lookup("rmi://localhost:1099/AuthService")` et invoque les méthodes nécessaires de façon complètement transparente pour l'utilisateur, cachant la complexité des communications TCP/IP.

---

## 4. Intégration avec les Serveurs SMTP, POP3 et IMAP

L'interface RMI `AuthService` intègre les méthodes indispensables pour que les serveurs périphériques l'utilisent comme source de vérité de l'identité des utilisateurs :
- **`authenticate(username, password)`** : Utilisée par les serveurs **POP3** et **IMAP** lors des commandes de connexion (`USER`/`PASS` ou `LOGIN`). Si la paire de clé est reconnue, le serveur RMI retourne le token JWT au coordinateur POP3/IMAP, prouvant le succès de la connexion.
- **`userExists(username)`** : Utilisée par le **serveur SMTP** pour valider très rapidement la présence d'un expéditeur lors de l'exécution d'une commande `MAIL FROM <...>`, évitant les usurpations rudimentaires.

---

## 5. Validation et Tests des Scénarios

Le système répond à l'ensemble des scénarios de test requis :

1. **Création d'un compte** : Une requête d'ajout depuis le client Swing (RMI) persiste immédiatement les données en RAM et met à jour le fichier `users.json`.
2. **Suppression & Révocation** : Un compte supprimé via le client d'administration n'est immédiatement plus résoluble via la méthode `userExists()`. Les accès SMTP lui seront donc interdits, et une tentative d'authentification IMAP/POP3 renverra un refus de service (mot de passe rejeté).
3. **Authentification** : Une demande d'authentification avec des *identifiants valides* est récompensée par un JWT cryptographiquement sûr, tandis que des *identifiants invalides* retourneront logiquement `null`.
4. **Découplage** : Les serveurs de messagerie finaux ne lisent ou n'écrivent jamais directement le fichier `users.json`, respectant intégralement l'architecture distribuée.
