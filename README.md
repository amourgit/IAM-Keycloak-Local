# EIGEN IAM — Keycloak Local

> **IAM par Établissement — Système EIGEN, République Gabonaise**

Un seul repository, une seule image Docker, déployé dans chaque établissement avec sa propre configuration. La variable `ETABLISSEMENT_CODE` différencie chaque instance.

---

## Architecture par établissement

```
[Internet]
     │
[Traefik] (TLS)
     │
[Keycloak Local realm-{code}]
     ├── SPI : eigen-kafka-event-listener   → événements sur Kafka local
     ├── SPI : eigen-session-manager        → sessions actives dans Redis
     ├── Thème : eigen-local                → UI brandée EIGEN
     ├── User Federation : LDAP/AD          → si l'établissement en dispose
     ├── Identity Provider : KC Central     → SSO inter-établissements
     └── JWKS : /realms/realm-{code}/...    → clés pour le Gateway Master
          │
     [PostgreSQL]         persistance Keycloak
     [Redis]              sessions actives ← interrogé par le Gateway
     [Kafka]              événements EIGEN
```

---

## Structure du repository

```
IAM-Keycloak-Local/
├── docker-compose.yml                  Stack complet (KC + PG + Redis)
├── Dockerfile                          Image Keycloak préconfigurée
├── .env.example                        Template de configuration
├── keycloak/
│   ├── realm-config/
│   │   ├── eigen-local-realm-template.json  Template realm paramétrable
│   │   └── realm-{code}.json               Fichier généré par établissement
│   ├── themes/
│   │   └── eigen-local/                    Thème visuel local EIGEN
│   └── extensions/
│       ├── eigen-kafka-listener/           SPI Kafka (même que Central)
│       └── eigen-session-manager/          SPI Redis sessions (LOCAL uniquement)
├── postgres/init/                      Schéma audit PostgreSQL
├── redis/redis.conf                    Configuration Redis sessions
├── scripts/
│   ├── generer-realm.sh                Génère realm-{code}.json depuis template
│   ├── init-keycloak-local.sh          Init post-démarrage (LDAP, secrets, rôles)
│   └── build-spis.sh                   Build des deux JARs Maven
└── docs/etablissements/                Docs de configuration par établissement
```

---

## Démarrage rapide

### 1. Générer le fichier realm pour l'établissement

```bash
./scripts/generer-realm.sh \
    --code uob \
    --nom "Université Omar Bongo"
```

### 2. Configurer l'environnement

```bash
cp .env.example .env
# Éditer .env avec ETABLISSEMENT_CODE, KC_HOSTNAME, secrets...
```

### 3. Builder les SPIs

```bash
./scripts/build-spis.sh
```

### 4. Démarrer

```bash
docker-compose up -d
```

### 5. Vérification

```bash
# JWKS — clés publiques que le Gateway utilisera
curl http://localhost:8090/realms/realm-uob/protocol/openid-connect/certs

# Sessions Redis (doit être vide au démarrage)
redis-cli -a $REDIS_PASSWORD KEYS "eigen:session:*"
```

### 6. Init post-démarrage

```bash
./scripts/init-keycloak-local.sh
```

---

## SPIs inclus

### `eigen-kafka-event-listener`
Publie sur Kafka local tous les événements d'authentification.

Topics produits (préfixe `eigen.local.{code}`) :
- `.session.ouverte` / `.session.fermee`
- `.authentification.echec`
- `.compte.cree` / `.compte.modifie` / `.compte.desactive`
- `.audit`

### `eigen-session-manager` ⭐ (LOCAL uniquement)
**Le composant critique du Gateway.** À chaque connexion réussie, écrit dans Redis :

```
clé   : eigen:session:{etablissement}:{user_id}
valeur: JSON avec métadonnées de session (type_profil, identifiant_national, etc.)
TTL   : SESSION_TTL_SECONDS (défaut 86400s = 24h)
```

Au logout ou désactivation admin → supprime la clé immédiatement.

**Impact si Redis est down** : mode fail-secure — le Gateway refusera toutes les requêtes jusqu'au rétablissement de Redis.

---

## User Federation LDAP/AD

Configurer `LDAP_ENABLED=true` dans `.env` pour activer la fédération avec un Active Directory d'établissement.

Mappings LDAP configurés automatiquement :
- `sAMAccountName` → `username`
- `givenName` → `firstName`
- `sn` → `lastName`
- `mail` → `email`
- `employeeID` → `identifiant_national` (lien avec EIGEN National)
- `department` → `departement_ad`

---

## Établissements déployés

| Code | Nom | LDAP | Port HTTP |
|------|-----|------|-----------|
| `uob` | Université Omar Bongo | ✓ | 8090 |
| `esga` | École Supérieure de Gestion | ✗ | 8091 |
| `insg` | Institut National des Sciences de Gestion | ✗ | 8092 |
| `ustm` | Université des Sciences et Techniques de Masuku | ✗ | 8093 |

---

## Sécurité

- Sessions Redis avec TTL automatique — pas de sessions fantômes
- Révocation immédiate via suppression Redis lors du logout ou suspension admin
- Fail-secure si Redis indisponible (Gateway refuse toutes les requêtes)
- User Federation en READ_ONLY — Keycloak ne modifie jamais l'AD
- Secrets gérés via HashiCorp Vault en production

---

© 2025 EIGEN — Ministère de l'Enseignement Supérieur, République Gabonaise
