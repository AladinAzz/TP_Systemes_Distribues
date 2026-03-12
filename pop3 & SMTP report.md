# Rapport Détaillé des Bugs et Corrections — Serveurs SMTP & POP3

## Introduction

Ce rapport documente l'ensemble des bugs et violations de protocole identifiés dans les serveurs SMTP (`SmtpServer.java`) et POP3 (`Pop3Server.java`), en se basant sur les spécifications **RFC 5321** (SMTP) et **RFC 1939** (POP3). Chaque bug est décrit avec son impact, la correction appliquée et le résultat du test de vérification.

---

## Bugs dans le Serveur SMTP (`SmtpServer.java`)

### Bug 1 — Réponse EHLO identique à HELO

| Élément | Détail |
|---|---|
| **RFC** | RFC 5321, Section 4.1.1.1 |
| **Sévérité** | Élevée |

**Problème :**
Les commandes `HELO` et `EHLO` étaient traitées de manière identique dans un même `case` du `switch` :

```java
case "HELO":
case "EHLO":
    handleHelo(argument);
    break;
```

Les deux renvoyaient une seule ligne `250 Hello <arg>`. Or, la RFC 5321 exige que `EHLO` retourne une réponse multiligne `250-` listant les extensions supportées.

**Correction :**
Séparation en deux handlers distincts. `EHLO` retourne désormais une réponse multiligne conforme :

```java
case "HELO":
    handleHelo(argument);
    break;
case "EHLO":
    handleEhlo(argument);
    break;
```

```java
private void handleEhlo(String arg) {
    state = SmtpState.HELO_RECEIVED;
    sender = "";
    recipients.clear();
    out.println("250-smtp.example.com Hello " + arg);
    out.println("250 OK");
}
```

**Test :**
```
Client: EHLO test.com
Serveur: 250-smtp.example.com Hello test.com   ✅ (ligne de continuation)
Serveur: 250 OK                                 ✅ (ligne finale)
```

---

### Bug 2 — Absence de transparence (dot-stuffing) dans DATA

| Élément | Détail |
|---|---|
| **RFC** | RFC 5321, Section 4.5.2 |
| **Sévérité** | Critique |

**Problème :**
Lors de la réception du corps du message (état `DATA_RECEIVING`), le serveur ne gérait pas la transparence des points. Si un client envoyait une ligne commençant par un point supplémentaire (convention de "dot-stuffing"), le serveur stockait la ligne telle quelle au lieu de retirer le point de tête.

Code original :
```java
} else {
    dataBuffer.append(line).append("\r\n");
}
```

**Correction :**
Ajout de la logique de suppression du point de tête conformément à la RFC :

```java
} else {
    // RFC 5321 Section 4.5.2 - Transparency (dot-stuffing)
    if (line.startsWith(".")) {
        line = line.substring(1);
    }
    dataBuffer.append(line).append("\r\n");
}
```

**Test :**
Un email contenant la ligne `..this line starts with a dot` a été envoyé. Le serveur a correctement retiré le premier point et stocké `.this line starts with a dot`. ✅

---

### Bug 3 — Commande RSET manquante

| Élément | Détail |
|---|---|
| **RFC** | RFC 5321, Section 4.1.1.5 |
| **Sévérité** | Élevée |

**Problème :**
La commande `RSET` (Reset) n'était pas implémentée. Cette commande est **obligatoire** et permet au client d'annuler la transaction en cours et de revenir à l'état initial (après `HELO`).

**Correction :**
Ajout du handler `RSET` dans le `switch` et implémentation de la méthode :

```java
private void handleRset() {
    sender = "";
    recipients.clear();
    dataBuffer.setLength(0);
    if (state != SmtpState.CONNECTED) {
        state = SmtpState.HELO_RECEIVED;
    }
    out.println("250 OK");
}
```

**Test :**
```
Client: RSET
Serveur: 250 OK   ✅
```

---

### Bug 4 — Commande NOOP manquante

| Élément | Détail |
|---|---|
| **RFC** | RFC 5321, Section 4.1.1.9 |
| **Sévérité** | Moyenne |

**Problème :**
La commande `NOOP` (No Operation) n'était pas implémentée. Elle est obligatoire et permet au client de tester la connexion sans effet de bord.

**Correction :**

```java
private void handleNoop() {
    out.println("250 OK");
}
```

**Test :**
```
Client: NOOP
Serveur: 250 OK   ✅
```

---

### Bug 5 — `MAIL FROM` accepté sans `HELO`/`EHLO` préalable

| Élément | Détail |
|---|---|
| **RFC** | RFC 5321, Section 4.1.4 |
| **Sévérité** | Élevée |
| **Découvert lors de** | Tests d'erreurs |

**Problème :**
`handleMailFrom()` ne vérifiait pas l'état de la session. Un client pouvait envoyer `MAIL FROM` immédiatement après la connexion, sans avoir envoyé `HELO` ou `EHLO`, et le serveur acceptait avec `250 OK`.

**Correction :**
Ajout d'une vérification de l'état en début de méthode :

```java
private void handleMailFrom(String arg) {
    if (state == SmtpState.CONNECTED) {
        out.println("503 Bad sequence of commands");
        return;
    }
    // ... suite du traitement
}
```

**Test :**
```
(avant HELO)
Client: MAIL FROM:<a@b.com>
Serveur: 503 Bad sequence of commands   ✅
```

---

### Bug 6 — Fuite de sortie de debug vers le client

| Élément | Détail |
|---|---|
| **Type** | Bug fonctionnel |
| **Sévérité** | Élevée |
| **Découvert lors de** | Tests d'erreurs |

**Problème :**
Dans `handleMailFrom()`, lorsque le format de l'adresse était invalide, une ligne de debug envoyait l'argument brut au client :

```java
if (!arg.toUpperCase().matches("^FROM:\\s*<[^>]+>$")) {
    out.println("501 Syntax error in parameters or arguments");
    out.println(arg.toUpperCase());  // ← BUG: fuite de debug
    return;
}
```

Cela causait l'envoi d'une ligne supplémentaire non conforme au protocole, désynchronisant les échanges client/serveur.

**Correction :**
Suppression de la ligne `out.println(arg.toUpperCase());`.

**Test :**
```
Client: MAIL FROM:badformat
Serveur: 501 Syntax error in parameters or arguments   ✅ (une seule ligne)
```

---

## Bugs dans le Serveur POP3 (`Pop3Server.java`)

### Bug 7 — Absence de byte-stuffing dans RETR

| Élément | Détail |
|---|---|
| **RFC** | RFC 1939, Section 3 |
| **Sévérité** | Critique |

**Problème :**
Lors de l'envoi du contenu d'un email via `RETR`, les lignes étaient transmises telles quelles. Si une ligne du message commençait par un point (`.`), le client POP3 interpréterait cette ligne comme le marqueur de fin de message, tronquant ainsi l'email.

Code original :
```java
while ((line = reader.readLine()) != null) {
    out.println(line);
}
```

**Correction :**
Ajout du byte-stuffing : un point supplémentaire est ajouté en tête de toute ligne commençant par un point :

```java
while ((line = reader.readLine()) != null) {
    if (line.startsWith(".")) {
        out.println("." + line);
    } else {
        out.println(line);
    }
}
```

**Test :**
Un email contenant une ligne commençant par `.` est correctement transmis avec le double-point, et le client peut retirer le point de tête. ✅

---

### Bug 8 — Commande NOOP manquante

| Élément | Détail |
|---|---|
| **RFC** | RFC 1939 |
| **Sévérité** | Moyenne |

**Problème :**
La commande `NOOP` n'était pas implémentée, rendant le serveur non conforme.

**Correction :**

```java
private void handleNoop() {
    if (!authenticated) {
        out.println("-ERR Authentication required");
        return;
    }
    out.println("+OK");
}
```

**Test :**
```
(après authentification)
Client: NOOP
Serveur: +OK   ✅

(avant authentification)
Client: NOOP
Serveur: -ERR Authentication required   ✅
```

---

## Résumé des Corrections

| # | Bug | Fichier | RFC | Sévérité | Statut |
|---|---|---|---|---|---|
| 1 | EHLO = HELO | SmtpServer.java | 5321 §4.1.1.1 | Élevée | ✅ Corrigé |
| 2 | Pas de dot-stuffing (DATA) | SmtpServer.java | 5321 §4.5.2 | Critique | ✅ Corrigé |
| 3 | RSET manquant | SmtpServer.java | 5321 §4.1.1.5 | Élevée | ✅ Corrigé |
| 4 | NOOP manquant (SMTP) | SmtpServer.java | 5321 §4.1.1.9 | Moyenne | ✅ Corrigé |
| 5 | MAIL FROM sans HELO | SmtpServer.java | 5321 §4.1.4 | Élevée | ✅ Corrigé |
| 6 | Fuite debug vers client | SmtpServer.java | — | Élevée | ✅ Corrigé |
| 7 | Pas de byte-stuffing (RETR) | Pop3Server.java | 1939 §3 | Critique | ✅ Corrigé |
| 8 | NOOP manquant (POP3) | Pop3Server.java | 1939 | Moyenne | ✅ Corrigé |

## Résultats des Tests

- **Tests de fonctionnement normal** : SMTP ✅ | POP3 ✅
- **Tests d'erreurs SMTP** : **8/8** scénarios rejetés correctement
- **Tests d'erreurs POP3** : **15/15** scénarios rejetés correctement
