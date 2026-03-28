# Plan de Test Intégral du Système de Messagerie Distribué

Ce document décrit la stratégie globale et les scénarios de test pour valider le fonctionnement de bout en bout du système de messagerie, intégrant :
- Le serveur d'authentification centralisé (Java RMI & JWT).
- Le serveur SMTP (Envoi d'e-mails).
- Le serveur POP3 (Réception d'e-mails simple).
- Le serveur IMAP (Gestion avancée des boîtes mail).
- Les interfaces de supervision web associées.

## Objectifs des Tests
- Vérifier la robustesse de chaque composant de manière unitaire.
- S'assurer que les serveurs (SMTP, POP3, IMAP) se synchronisent correctement avec le serveur RMI.
- S'assurer de la persistance correcte des données d'authentification (`users.json`).
- Vérifier que les interfaces Web reçoivent bien la télémétrie des serveurs.

---

## 1. Tests du Système d'Authentification (RMI)

Ces tests s'assurent que le référentiel des utilisateurs est fiable.

| ID | Description du Test | Prérequis | Actions effectuées | Résultat Attendu |
|:---|:---|:---|:---|:---|
| **AUTH-01** | **Création d'un utilisateur** | Lancer `AuthServerMain` et `AuthClientMain`. | Depuis l'interface client (Swing), ajouter un utilisateur `testuser` avec mdp `123456`. | Message de succès. L'utilisateur apparaît dans la liste. Le fichier `users.json` est mis à jour. |
| **AUTH-02** | **Création d'un doublon** | L'utilisateur `testuser` existe. | Tenter de recréer `testuser`. | Message d'erreur empêchant les doublons. |
| **AUTH-03** | **Authentification réussie** | `testuser` existe avec mdp `123456`. | Appel direct RMI `authenticate("testuser", "123456")`. | Un Token JWT valide est retourné. |
| **AUTH-04** | **Authentification échouée** | `testuser` existe. | Appel RMI `authenticate("testuser", "mauvais_mdp")`. | La méthode retourne `null`. |
| **AUTH-05** | **Modification MDP** | `testuser` existe. | Depuis le client, modifier le mdp en `nouveau_mdp`. | Succès. L'auth avec l'ancien mdp échoue, celle avec le nouveau réussit. Le JSON est mis à jour. |
| **AUTH-06** | **Suppression d'un utilisateur**| `testuser` existe. | Depuis le client, bouton "Supprimer". | Suppression avec succès. L'utilisateur disparaît. Authentification désormais impossible. |

---

## 2. Tests du Serveur SMTP (Envoi avec Auth)

Ce module vérifie la capacité du serveur SMTP à relayer le courrier uniquement pour des utilisateurs qui existent, validés par RMI.

| ID | Description du Test | Prérequis | Actions effectuées | Résultat Attendu |
|:---|:---|:---|:---|:---|
| **SMTP-01** | **Envoi standard valide** | Auth Server lancé, Serveur SMTP lancé, `sender@mail.com` existe. | Connexion Telnet au port SMTP, commande `MAIL FROM: <sender@mail.com>`. | `250 OK`. SMTP a validé l'existence avec le RMI `userExists`. |
| **SMTP-02** | **Expéditeur inconnu** | Auth Server lancé, `fake@mail.com` n'existe pas. | Commande `MAIL FROM: <fake@mail.com>`. | `550 No such user here` (Refus). |
| **SMTP-03** | **Envoi de bout en bout** | Serveur SMTP prêt, destinataire valide. | `HELO`, `MAIL FROM`, `RCPT TO`, `DATA` (contenu), `.`, `QUIT`. | L'email est écrit sur le disque/mémoire de la boîte cible. Les logs SMTP affichent un succès. |
| **SMTP-04** | **Ordre des commandes invalide**| Serveur SMTP lancé. | Tenter d'envoyer la commande `DATA` avant `MAIL FROM`. | Réponse `503 Bad sequence of commands`. |

---

## 3. Tests POP3 / IMAP (Lecture avec Auth et JWT)

Vérification des serveurs de récupération d'e-mails.

| ID | Description du Test | Prérequis | Actions effectuées | Résultat Attendu |
|:---|:---|:---|:---|:---|
| **POP-01** | **Authentification POP3 (Succès)** | `user1` existe avec `mdp123`. | Telnet au port POP3, `USER user1`, `PASS mdp123`. | `+OK User successfully logged on`. (Le serveur POP3 obtient le JWT du RMI). |
| **POP-02** | **Authentification POP3 (Échec)** | `user1` existe. | Telnet POP3, `USER user1`, `PASS faux_mdp`. | `-ERR Authentication failed`. |
| **IMP-01** | **Authentification IMAP (Succès)** | `user2` / `secret`. | Telnet au port IMAP, `A1 LOGIN user2 secret`. | `A1 OK LOGIN completed`. (Obtention du JWT). |
| **IMP-02** | **Sélection de boîte (IMAP)** | Connecté en IMAP. | Envoyer `A2 SELECT INBOX`. | `A2 OK [READ-WRITE] SELECT completed`, affiche le bon nombre d'e-mails. |
| **POP-03** | **Récupération des emails (POP3)**| Connecté en POP3, `user1` a 2 emails. | Envoyer `STAT`, puis `RETR 1`. | Le serveur retourne la taille correcte et le contenu de l'e-mail 1. |

---

## 4. Tests des Interfaces de Supervision (Web / Spring Boot)

Vérification des accès web pour monitorer le bon fonctionnement système.

| ID | Description du Test | Prérequis | Actions effectuées | Résultat Attendu |
|:---|:---|:---|:---|:---|
| **WEB-01** | **Dashboard SMTP** | Démarrer le module SMTP Spring Boot. | Naviguer sur `http://localhost:8080`. | L'interface s'affiche avec le statut (Démarré), et la console de log. |
| **WEB-02** | **Télémétrie en temps réel** | Interface Web SMTP ou IMAP ouverte. | Effectuer une session de test client (Telnet SMTP/IMAP). | Les commandes tapées (ex: `A1 LOGIN...`, `MAIL FROM`) s'affichent instantanément en temps réel sur l'interface web. |
| **WEB-03** | **Stop/Start SMTP depuis le Web** | Dash Web SMTP ouvert. | Appuyer sur "Stop Server", puis "Start Server". | Le port réseau système est libéré (Stop) puis se rouvre (Start). Les clients Telnet perdent la connexion lors de l'arrêt. |
| **WEB-04** | **Dashboards Multiples** | Démarrer POP3 (8081) et IMAP (8082). | Ouvrir simultanément `localhost:8081` et `8082` | Chaque interface supervise son propre protocole indépendamment. |

---

## Procédure d'Exécution Conseillée (Workflow)

1. Démarrer le serveur **RMI Auth-Server**.
2. Démarrer le client **RMI Auth-Client** (Swing) et configurer 2 utilisateurs (`userA`, `userB`).
3. Lancer indépendamment les serveurs via leurs modules **Spring Boot** (SMTP, POP3, IMAP).
4. Ouvrir un navigateur et disposer les **3 interfaces Web** côte-à-côte (Ports 8080, 8081, 8082).
5. Ouvrir un terminal et simuler l'envoi d'un email par **Telnet SMTP** (`userA` -> `userB`).
6. Ouvrir un second terminal et récupérer l'e-mail par **Telnet POP3 ou IMAP** avec `userB`.
7. Interagir avec l'application **Client Swing** (supprimer un compte, puis ré-essayer de se connecter en Telnet pour vérifier que la suspension est immédiate).
