# Migration MySQL + REST API — Plan d'Implémentation

## Contexte

Le système de messagerie distribué repose actuellement sur :
- **Stockage** : fichiers `.txt` (emails) + `mailserver/users.json` (comptes)
- **Auth** : Java RMI sur le port 1099 — `Auth-Server` expose `IAuthService` via registre RMI
- **Clients RMI** : `SmtpSession`, `Pop3Session`, `ImapSession` font chacun un `Naming.lookup()` cacheé par session
- **Auth-Client** : CLI Java basique (`AuthCliClient`) qui appelle aussi le registre RMI
- **SMTP/POP3/IMAP** : Spring Boot (port 8080/8081/8082) avec WebSocket pour les logs, Java pur pour le protocole

L'objectif est de remplacer les deux couches de persistance (JSON → MySQL) et de communication (RMI → REST), sans casser l'interface WebSocket ni la logique protocolaire.

---

## User Review Required

> [!IMPORTANT]
> **`Auth-API` (module RMI)** : après la migration, ce module n'a plus de raison d'exister (l'interface RMI `IAuthService` disparaît). Il est proposé de le **supprimer** du projet. Si vous souhaitez le garder pour références académiques, ce n'est pas un problème — il restera simplement inutilisé.

> [!IMPORTANT]
> **`Auth-Client`** : actuellement c'est un simple CLI (`AuthCliClient`). Le plan le **remplace entièrement** par un client HTTP (`AuthRestClient`) + le maintient le CLI mais en passant par REST. Confirmez si vous souhaitez aussi une interface Swing GUI, ou si le CLI suffit.

> [!WARNING]
> **Compatibilité Java 22 + `java.net.http`** : `HttpClient` est disponible nativement depuis Java 11. Aucune dépendance externe n'est nécessaire pour le client REST — cela correspond au JDK 22 déjà utilisé.

> [!CAUTION]
> **Migration des données existantes** : les emails stockés en fichiers `.txt` et les comptes dans `users.json` **ne seront pas migrés automatiquement**. Les comptes devront être recréés via l'API REST après lancement. Précisez si un script de migration des données existantes est nécessaire.

---

## Proposed Changes

### DB-Core (nouveau module)

#### [NEW] `DB-Core/pom.xml`
Nouveau module Maven enfant. Dépendances : `mysql-connector-j:8.3.0`, `HikariCP:5.1.0`.

#### [NEW] `DB-Core/src/main/java/org/example/db/DatabaseManager.java`
Pool de connexions HikariCP. URL/user/password configurables via variables d'environnement (`DB_URL`, `DB_USER`, `DB_PASS`) avec fallback `localhost:3306/messagerie` / `mailuser` / `mailpass`.

#### [NEW] `DB-Core/src/main/java/org/example/db/UserRepository.java`
Encapsule les 5 procédures stockées liées aux utilisateurs :
- `authenticate_user` → retourne hash+salt, compare en Java
- `store_user` / `update_user` / `delete_user` / `fetch_all_users`

#### [NEW] `DB-Core/src/main/java/org/example/db/EmailRepository.java`
Encapsule les procédures email :
- `store_email(sender, recipient, subject, body)` — appelé pour chaque destinataire
- `fetch_emails(username)` → `List<EmailRecord>` (id, sender, recipient, subject, body, sentAt, flags)
- `delete_email(id)` (soft delete — `is_deleted = TRUE`)
- `update_flags(id, flags)` — pour IMAP

#### [NEW] `DB-Core/src/main/java/org/example/db/EmailRecord.java`
POJO représentant une ligne de la table `emails` (les sessions IMAP/POP3 l'utilisent à la place de `File`).

#### [NEW] `DB-Core/src/main/resources/schema.sql`
DDL complet : `CREATE TABLE users`, `CREATE TABLE emails`, et les 6 procédures stockées. Sert de documentation et peut être exécuté manuellement.

---

### Auth-Server (remplacement RMI → Spring Boot REST)

#### [MODIFY] `Auth-Server/pom.xml`
- Supprimer la dépendance `Auth-API` (RMI)
- Ajouter `spring-boot-starter-web`, `DB-Core`, conserver `jjwt-*` et `gson` (pour compat si besoin)
- Ajouter plugin `spring-boot-maven-plugin` pour packaging exécutable

#### [DELETE] `Auth-Server/src/main/java/org/example/server/AuthServiceImpl.java`
Remplacé par le contrôleur REST.

#### [DELETE] `Auth-Server/src/main/java/org/example/server/AuthServerMain.java`
Remplacé par `@SpringBootApplication`.

#### [DELETE] `Auth-Server/src/main/java/org/example/server/UserManager.java`
Remplacé par `UserRepository` dans DB-Core.

#### [NEW] `Auth-Server/src/main/java/org/example/server/AuthServerApplication.java`
Point d'entrée Spring Boot (`@SpringBootApplication`). Port : **8090**.

#### [NEW] `Auth-Server/src/main/java/org/example/server/AuthController.java`
`@RestController @RequestMapping("/auth")` — expose :

| Méthode | URL | Corps | Retour |
|---|---|---|---|
| `POST` | `/auth/authenticate` | `{username, password}` | `{token}` ou 401 |
| `GET` | `/auth/users/{username}/exists` | — | `{exists: bool}` |
| `POST` | `/auth/users` | `{username, password}` | 201 ou 409 |
| `PUT` | `/auth/users/{username}/password` | `{newPassword}` | 200 ou 404 |
| `DELETE` | `/auth/users/{username}` | — | 200 ou 404 |
| `GET` | `/auth/users` | — | `["user1","user2"]` |

`JwtManager` est gardé tel quel (déplacé dans Auth-Server, plus de lien avec l'API RMI).

#### [NEW] `Auth-Server/src/main/resources/application.properties`
```
server.port=8090
db.url=jdbc:mysql://localhost:3306/messagerie
db.user=mailuser
db.pass=mailpass
```

---

### Auth-Client (remplacement RMI → `HttpClient` Java 11+)

#### [MODIFY] `Auth-Client/pom.xml`
- Supprimer dépendance `Auth-API`
- Ajouter `jackson-databind` (pour sérialiser/désérialiser JSON) ou utiliser `org.json` léger
- Ajouter `DB-Core` **non** — Auth-Client ne touche pas la base

#### [DELETE] `Auth-Client/src/main/java/org/example/client/AuthCliClient.java`
Remplacé par version redessinée.

#### [NEW] `Auth-Client/src/main/java/org/example/client/AuthRestClient.java`
Client HTTP réutilisable (pas un `main`) — utilisé à la fois par le CLI et par SMTP/POP3/IMAP :
```java
public class AuthRestClient {
    private static final String BASE = "http://localhost:8090/auth";
    public String authenticate(String u, String p) { ... }
    public boolean userExists(String u) { ... }
    public boolean createUser(String u, String p) { ... }
    public boolean updateUser(String u, String p) { ... }
    public boolean deleteUser(String u) { ... }
    public List<String> getAllUsers() { ... }
}
```
Utilise `java.net.http.HttpClient` (Java 11+, aucune dépendance externe). Parsing JSON via `String.contains()` pour les réponses simples, ou `ObjectMapper` si Jackson est inclus.

#### [NEW] `Auth-Client/src/main/java/org/example/client/AuthClientMain.java`
Reprend la logique CLI de `AuthCliClient` mais en instanciant `AuthRestClient` :
```bash
java -jar auth-client.jar create alice secret123
java -jar auth-client.jar authenticate alice secret123
```

---

### SMTP — Migration RMI → REST + fichiers → MySQL

#### [MODIFY] `SMTP/pom.xml`
- Supprimer dépendance `Auth-API`
- Ajouter `Auth-Client` (contient `AuthRestClient`)
- Ajouter `DB-Core` (contient `EmailRepository`)

#### [MODIFY] `SMTP/src/main/java/org/example/SmtpServer.java`

**Dans `SmtpSession`** :
1. Remplacer `IAuthService authService` par `AuthRestClient authClient`
2. Remplacer `getAuthService()` (RMI lookup) par `new AuthRestClient()` (instanciation locale)
3. Dans `handleMailFrom()` : `svc.userExists(username)` → `authClient.userExists(username)` — **aucune `RemoteException` à gérer**
4. Dans `storeEmail()` : remplacer l'écriture fichier par `emailRepository.storeEmail(sender, recipient, subject, body)` pour chaque destinataire
5. Supprimer `resolveMailserverDir()` (inutile si MySQL)
6. Dans `handleRcptTo()` : supprimer la création de répertoire `mailserver/<user>/`

---

### POP3 — Migration RMI → REST + fichiers → MySQL

#### [MODIFY] `POP3/pom.xml`
Même ajouts que SMTP.

#### [MODIFY] `POP3/src/main/java/org/example/Pop3Server.java`

**Dans `Pop3Session`** :
1. Remplacer `IAuthService authService` / RMI par `AuthRestClient authClient`
2. Remplacer `private List<File> emails` par `private List<EmailRecord> emails`
3. Dans `handlePass()` :
   - `svc.authenticate(username, arg)` → `authClient.authenticate(username, arg)`
   - Supprimer résolution du `userDir` et `.listFiles()`
   - Remplacer par `emails = emailRepository.fetchEmails(username)`
4. Dans `handleStat()` : `emails.stream().mapToLong(File::length).sum()` → `emails.stream().mapToLong(r -> r.getBody().length()).sum()`
5. Dans `handleList()` : `emails.get(i).length()` → `emails.get(i).getBody().length()`
6. Dans `handleRetr()` : lire depuis `EmailRecord.getBody()` au lieu d'un `FileReader`
7. Dans `handleQuit()` : pour chaque index marqué, appeler `emailRepository.deleteEmail(emails.get(idx).getId())`
8. Supprimer `resolveMailserverDir()`

---

### IMAP — Migration RMI → REST + fichiers → MySQL + flags en colonne

#### [MODIFY] `IMAP/pom.xml`
Même ajouts que SMTP.

#### [MODIFY] `IMAP/src/main/java/org/example/ImapServer.java`

**Dans `ImapSession`** :
1. Remplacer RMI par `AuthRestClient`
2. Remplacer `List<File> emails` par `List<EmailRecord> emails`
3. Dans `handleLogin()` : `svc.authenticate()` → `authClient.authenticate()` ; supprimer création `userDir`
4. Dans `handleSelect()` : `userDir.listFiles()` → `emailRepository.fetchEmails(username)`
5. Dans `handleFetch()` : `getHeaders(File)`, `getBody(File)`, `getFullContent(File)` → méthodes lisant depuis `EmailRecord.getBody()` (parse headers/body depuis le contenu stocké)
6. Flags (`getFlags`, `addFlag`, `removeFlag`, `setFlags`) : au lieu de lire/écrire des `.flags` files → `emailRepository.updateFlags(id, flagsString)`
7. Dans `handleSearch()` : filtrer sur `EmailRecord` au lieu de parser des fichiers
8. Supprimer tous les helpers `File`-based (`resolveMailserverDir`, `getHeaders(File)`, etc.)

---

### Root POM + Auth-API

#### [MODIFY] `pom.xml`
- Ajouter `<module>DB-Core</module>`
- Garder `<module>Auth-API</module>` ou le supprimer (cf. question ouverte)

#### [DELETE optionnel] `Auth-API/`
Si supprimé, retirer du POM parent et s'assurer qu'aucun module n'en dépend plus.

---

## Open Questions

> [!IMPORTANT]
> **1. Supprimer ou garder `Auth-API` ?** Le module RMI n'est plus nécessaire. Vous pouvez le garder pour la soutenance comme référence de l'ancienne implémentation, mais il faudra retirer les dépendances Maven vers lui.

> [!IMPORTANT]
> **2. GUI Swing pour Auth-Client ?** Le plan conserve uniquement le CLI. Si vous voulez une interface graphique (Swing) pour gérer les comptes utilisateurs, c'est faisable en plus.

> [!WARNING]
> **3. Migration des données existantes ?** Les comptes `users.json` et les emails `.txt` ne seront pas importés automatiquement. Faut-il un script de migration ?

> [!IMPORTANT]
> **4. Variable d'environnement ou properties ?** Les credentials MySQL seront dans `application.properties` (Auth-Server) et dans des variables d'environnement pour DB-Core. Confirmez si une approche centralisée (un seul `application.properties`) est préférable.

---

## Ordre d'implémentation

```
Étape 1 — Créer la base MySQL + exécuter schema.sql (manuel, hors code)
Étape 2 — Créer DB-Core (DatabaseManager + UserRepository + EmailRepository + EmailRecord)
Étape 3 — Migrer Auth-Server vers Spring Boot REST (AuthController, application.properties)
Étape 4 — Créer Auth-Client (AuthRestClient + CLI reimp.)
Étape 5 — Migrer SMTP (POM + SmtpSession : RMI→REST + fichier→MySQL)
Étape 6 — Migrer POP3 (POM + Pop3Session : RMI→REST + fichier→MySQL)
Étape 7 — Migrer IMAP (POM + ImapSession : RMI→REST + fichier→MySQL + flags)
Étape 8 — Mettre à jour root POM, supprimer Auth-API si décidé
Étape 9 — Tests : curl Auth-Server → SMTP envoi → POP3 récupération → IMAP flags
```

---

## Verification Plan

### Tests automatiques (curl)
```bash
# Créer un utilisateur
curl -X POST http://localhost:8090/auth/users -H "Content-Type: application/json" \
     -d '{"username":"alice","password":"secret"}'

# Authentifier
curl -X POST http://localhost:8090/auth/authenticate \
     -H "Content-Type: application/json" -d '{"username":"alice","password":"secret"}'

# Vérifier existence
curl http://localhost:8090/auth/users/alice/exists
```

### Tests protocolaires (telnet/netcat)
```bash
# SMTP : envoi d'un email alice → bob
# POP3 : récupération des emails de bob
# IMAP : SELECT INBOX, FETCH, STORE \Seen
```

### Vérification base de données
```sql
SELECT * FROM users;
SELECT * FROM emails WHERE recipient LIKE 'bob%';
```
# Walkthrough — Migration MySQL + REST API

La migration du système de messagerie distribué est terminée. Nous sommes passés d'une architecture basée sur des fichiers locaux et Java RMI à une architecture moderne utilisant **MySQL** pour la persistance et **Spring Boot REST** pour les communications inter-services.

## Changements Majeurs

### 1. Persistance des Données (Module `DB-Core`)
- **MySQL remplace les fichiers .txt et users.json**.
- Utilisation de **HikariCP** pour un pool de connexions performant.
- Implémentation de `UserRepository` et `EmailRepository` utilisant des **procédures stockées** pour garantir l'intégrité des opérations (stockage, fetch, soft delete, flags).

### 2. Authentification (Module `Auth-Server`)
- **REST remplace RMI**.
- Le serveur est désormais une application **Spring Boot** écoutant sur le port **8090**.
- Endpoints implémentés :
    - `POST /auth/authenticate` : Retourne un token JWT.
    - `GET /auth/users` : Liste les utilisateurs.
    - `POST /auth/users` : Crée un compte.
    - `DELETE /auth/users/{username}` : Désactive un compte.
- Maintien de la sécurité : Hachage SHA-256 avec Salt et jetons JWT signés.

### 3. Client d'Administration (`Auth-Client`)
- Transition vers `AuthRestClient` utilisant `java.net.http.HttpClient`.
- Mise à jour de l'interface **Swing GUI** (`AuthClientMain`) pour consommer l'API REST de manière transparente.

### 4. Serveurs de Protocoles (SMTP, POP3, IMAP)
- **SMTP** : Authentifie les expéditeurs via REST et stocke les emails entrants directement dans MySQL.
- **POP3** : Récupère la liste des emails et leur contenu depuis la base de données. Gère la suppression (soft delete).
- **IMAP** : Gestion complète des emails et des **Drapeaux (Flags)** en base de données (ex: `\Seen`, `\Deleted`).

## Vérification Effectuée

### Compilation
Un build complet via Maven a été effectué avec succès :
```powershell
./mvnw clean compile
```
> [!NOTE]
> Tous les modules compilent sans erreur, confirmant que les dépendances RMI ont été correctement remplacées par les nouvelles dépendances REST et DB.

### Schéma SQL
Le fichier [schema.sql](file:///c:/Users/derrm/Documents/System%20distrubue/TP_Systemes_Distribues/DB-Core/src/main/resources/schema.sql) est prêt à être importé dans une base MySQL locale nommée `messagerie`.

## Instructions pour le Déploiement

1. **MySQL** :
    - Créer la base `messagerie`.
    - Exécuter le script `DB-Core/src/main/resources/schema.sql`.
    - (Optionnel) Créer un utilisateur `mailuser` avec mot de passe `mailpass` (ou configurer via variables d'env).

2. **Lancement** :
    - Démarrer `Auth-Server` (Spring Boot).
    - Démarrer les serveurs `SMTP`, `POP3` et `IMAP`.
    - Utiliser `Auth-Client` pour créer vos premiers comptes.

---
> [!IMPORTANT]
> Le module **Auth-API** a été supprimé car le contrat RMI n'est plus nécessaire. Toute la logique client est désormais centralisée dans le module **Auth-Client**.
