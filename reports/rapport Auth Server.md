# Rapport d'Implémentation : Serveur d'Authentification Distribué (Java RMI & JWT)

Ce rapport détaille la conception, l'architecture et l'implémentation du service d'authentification centralisé pour le système de messagerie distribué. Ce service utilise **Java RMI** pour la communication réseau et les **JSON Web Tokens (JWT)** pour générer des jetons d'accès sécurisés.

> [!IMPORTANT]
> **Améliorations de Sécurité (FIXES) :**
> - Les mots de passe ne sont plus stockés en clair mais hachés avec **SHA-256 + Sel unique par utilisateur**.
> - La clé secrète JWT est désormais **persistante sur le disque** (`.jwt_secret`), garantissant que les jetons restent valides après un redémarrage du serveur.
> - La récupération du stub RMI est **mise en cache** par session sur les serveurs mail pour optimiser les performances.

---

## 1. Architecture du Système

Le système repose sur trois modules Java distincts :
- **Auth-API** : Interface distante commune.
- **Auth-Server** : Logique métier, stockage JSON, et sécurité.
- **Auth-Client** : Interface d'administration Swing.

---

## 2. Interface de Service (`IAuthService`)

L'interface définit le contrat de service exposé via RMI.

```java
public interface IAuthService extends Remote {
    String authenticate(String username, String password) throws RemoteException;
    boolean userExists(String username) throws RemoteException;
    boolean createUser(String username, String password) throws RemoteException;
    boolean updateUser(String username, String newPassword) throws RemoteException;
    boolean deleteUser(String username) throws RemoteException;
    List<String> getAllUsers() throws RemoteException;
}
```

---

## 3. Gestion de la Sécurité et Persistance

### 3.1. Hachage des Mots de Passe (`UserManager`)
La classe `UserManager` assure la persistance dans `users.json` et gère le hachage sécurisé.

```java
// Extrait de UserManager.java
private static String hashPassword(String password, String salt) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Base64.getDecoder().decode(salt));
        byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException("SHA-256 not available", e);
    }
}
```

### 3.2. Jetons JWT Persistants (`JwtManager`)
Pour éviter de déconnecter tous les utilisateurs à chaque redémarrage, la clé de signature est stockée dans `mailserver/.jwt_secret`.

```java
// Extrait de JwtManager.java
private Key loadOrCreateKey() {
    File f = new File(KEY_FILE);
    if (f.exists()) {
        byte[] encoded = Files.readAllBytes(Paths.get(KEY_FILE));
        byte[] decoded = Base64.getDecoder().decode(encoded);
        return Keys.hmacShaKeyFor(decoded);
    }
    // Sinon, génération et sauvegarde d'une nouvelle clé...
}
```

---

## 4. Intégration dans les Serveurs Mail

Les serveurs (SMTP, POP3, IMAP) utilisent un client RMI pour valider les requêtes.

**Exemple d'appel dans le serveur SMTP :**
```java
IAuthService svc = getAuthService(); // Récupération du stub RMI
String username = email.split("@")[0];
if (!svc.userExists(username)) {
    sendResponse("550 User unknown / Non autorisé");
    return;
}
```

---

## 5. Interface d'Administration (`Auth-Client`)

Le client Swing permet une gestion visuelle des comptes via des appels RPC transparents.

![Capture Interface Admin](C:/Users/derrm/Documents/System%20distrubue/TP_Systemes_Distribues/reports/images/admin_gui.png) (Simulation)

> [!TIP]
> **Performance** : L'utilisation du mot-clé `synchronized` dans `UserManager` combinée à une lecture unique au démarrage garantit une haute performance tout en préservant l'intégrité des données lors d'accès concurrents massifs entre SMTP, POP3 et IMAP.

