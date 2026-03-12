# Rapport Détaillé — Serveur IMAP (RFC 9051)

## 1. Architecture et Conception

### Automate à États Finis (FSM)

Le serveur IMAP implémente un automate à 4 états conformément à la RFC 9051 :

```
NOT_AUTHENTICATED ──LOGIN──▶ AUTHENTICATED ──SELECT──▶ SELECTED
        │                        │                        │
        └────────LOGOUT──────────┴────────LOGOUT──────────┘
                                                     ▼
                                                  LOGOUT
```

| État | Commandes Autorisées |
|---|---|
| `NOT_AUTHENTICATED` | `CAPABILITY`, `NOOP`, `LOGIN`, `LOGOUT` |
| `AUTHENTICATED` | `CAPABILITY`, `NOOP`, `SELECT`, `LOGOUT` |
| `SELECTED` | `CAPABILITY`, `NOOP`, `FETCH`, `STORE`, `SEARCH`, `SELECT`, `LOGOUT` |
| `LOGOUT` | (fin de session) |

### Stockage des Flags

Les indicateurs (flags) sont stockés dans des fichiers compagnons `.flags` :
- Email : `mailserver/user1/20260312_014529.txt`
- Flags : `mailserver/user1/20260312_014529.txt.flags`

Chaque fichier `.flags` contient un flag par ligne (ex : `\Seen`).

### Commandes Implémentées

| Commande | Description | Format de Réponse |
|---|---|---|
| `LOGIN user pass` | Authentification | `tag OK/NO` |
| `SELECT INBOX` | Sélection de boîte | `* N EXISTS`, `* FLAGS (...)`, `tag OK` |
| `FETCH seq (items)` | Lecture de messages | `* seq FETCH (...)`, `tag OK` |
| `STORE seq ±FLAGS (flags)` | Gestion des flags | `* seq FETCH (FLAGS (...))`, `tag OK` |
| `SEARCH critère` | Recherche | `* SEARCH seq1 seq2...`, `tag OK` |
| `LOGOUT` | Déconnexion | `* BYE ...`, `tag OK` |
| `CAPABILITY` | Capacités du serveur | `* CAPABILITY IMAP4rev2`, `tag OK` |
| `NOOP` | Pas d'opération | `tag OK` |

---

## 2. Résultats des Tests

### Scénario 1 : Flux de Base (LOGIN → SELECT → FETCH → LOGOUT)

```
Client: a001 LOGIN user1 pass
Serveur: a001 OK LOGIN completed

Client: a002 SELECT INBOX
Serveur: * 3 EXISTS
Serveur: * 0 RECENT
Serveur: * FLAGS (\Seen \Answered \Flagged \Deleted \Draft)
Serveur: * OK [PERMANENTFLAGS (\Seen \Deleted)]
Serveur: * OK [UNSEEN 1]
Serveur: a002 OK [READ-WRITE] SELECT completed

Client: a003 FETCH 1 (FLAGS)
Serveur: * 1 FETCH (FLAGS ())
Serveur: a003 OK FETCH completed

Client: a004 LOGOUT
Serveur: * BYE IMAP4rev2 Server logging out
Serveur: a004 OK LOGOUT completed
```
**Résultat : ✅ PASS** — Le format des réponses est conforme à la RFC 9051.

---

### Scénario 2 : Sélection de Boîte Inexistante

```
Client: a002 SELECT UNKNOWN
Serveur: a002 NO [NONEXISTENT] Mailbox does not exist
```
**Résultat : ✅ PASS** — Le serveur renvoie `NO` avec le code `[NONEXISTENT]` conformément à la RFC.

---

### Scénario 3 : Lecture Partielle (En-têtes Uniquement)

```
Client: a003 FETCH 1 (BODY[HEADER])
Serveur: * 1 FETCH (BODY[HEADER] {105}
Serveur: From: test@localhost
Serveur: To: user1@localhost
Serveur: Date: Sun, 23 Mar 2025 12:10:01 +0100
Serveur: Subject: Test Email
Serveur:
Serveur: )
Serveur: a003 OK FETCH completed
```
**Résultat : ✅ PASS** — Seuls les en-têtes sont retournés, le corps du message n'est pas inclus.

---

### Scénario 4 : Gestion des Flags

**Étape 1 — Marquer comme lu :**
```
Client: a003 STORE 1 +FLAGS (\Seen)
Serveur: * 1 FETCH (FLAGS (\Seen))
Serveur: a003 OK STORE completed
```

**Étape 2 — Vérifier dans la même session :**
```
Client: a004 FETCH 1 (FLAGS)
Serveur: * 1 FETCH (FLAGS (\Seen))
Serveur: a004 OK FETCH completed
```

**Étape 3 — Vérifier la persistance (nouvelle session) :**
```
Client: a003 FETCH 1 (FLAGS)
Serveur: * 1 FETCH (FLAGS (\Seen))
Serveur: a003 OK FETCH completed
```
**Résultat : ✅ PASS** — Le flag `\Seen` est conservé entre les sessions grâce aux fichiers `.flags`.

---

### Scénario 5 : Recherche

```
Client: a003 SEARCH ALL
Serveur: * SEARCH 1 2 3                    ← 3 messages trouvés

Client: a004 SEARCH FROM sender
Serveur: * SEARCH 3                         ← 1 message de "sender"

Client: a005 SEARCH SUBJECT Test
Serveur: * SEARCH 1 2 3                     ← tous contiennent "Test"

Client: a006 SEARCH UNSEEN
Serveur: * SEARCH 2 3                       ← 2 messages non lus
```
**Résultat : ✅ PASS** — Seuls les messages correspondants sont retournés.

---

### Scénario 6 : Commandes dans le Mauvais État

```
(avant LOGIN)
Client: a001 FETCH 1 (FLAGS)
Serveur: a001 BAD Command requires authentication     ✅

Client: a002 SELECT INBOX
Serveur: a002 BAD Command requires authentication     ✅

(après LOGIN, avant SELECT)
Client: a004 FETCH 1 (FLAGS)
Serveur: a004 BAD No mailbox selected                  ✅

Client: a005 BLAH
Serveur: a005 BAD Unknown command                      ✅

Client: a006 LOGIN user1 pass
Serveur: a006 BAD Already authenticated                ✅
```
**Résultat : ✅ PASS** — Toutes les commandes invalides sont rejetées avec un message d'erreur approprié.

---

## 3. Comparaison IMAP vs POP3

| Caractéristique | POP3 (RFC 1939) | IMAP (RFC 9051) |
|---|---|---|
| **Gestion des états** | 2 états (non authentifié, authentifié) | 4 états (non authentifié, authentifié, sélectionné, logout) |
| **Messages sur le serveur** | Téléchargés puis supprimés | Conservés sur le serveur |
| **Dossiers** | Non supportés | Supportés (INBOX, SENT, etc.) |
| **Flags (lu/non lu)** | Non supportés | Supportés (`\Seen`, `\Deleted`, etc.) |
| **Lecture partielle** | Message complet uniquement | En-têtes, corps, ou message complet |
| **Recherche** | Non supportée | Supportée (FROM, SUBJECT, UNSEEN, etc.) |
| **Commandes taggées** | Non (réponses +OK/-ERR) | Oui (tag par commande) |
| **Accès concurrent** | Non recommandé | Supporté |

---

## 4. Limites et Extensions Possibles

### Limites actuelles
- **Un seul dossier** : Seul `INBOX` est implémenté.
- **Pas de TLS** : Les communications ne sont pas chiffrées.
- **Authentification simplifiée** : Tout mot de passe est accepté.
- **Pas de UID** : La commande `UID FETCH` n'est pas supportée.

### Extensions possibles
- Ajout des dossiers `SENT`, `TRASH`, `DRAFTS` avec la commande `CREATE`.
- Implémentation de `COPY` pour déplacer des messages entre dossiers.
- Support de `STARTTLS` pour le chiffrement.
- Implémentation de `UID` pour des identifiants uniques persistants.
- Ajout de `IDLE` pour les notifications push en temps réel.
