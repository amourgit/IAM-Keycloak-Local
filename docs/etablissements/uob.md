# EIGEN IAM — Configuration Keycloak Local :: UOB

## Université Omar Bongo (UOB)

**Code** : `uob`  
**Domaine SSO** : `sso.uob.eigen.ga`  
**Realm** : `realm-uob`  
**Active Directory** : `ad.uob.ga` (LDAP_ENABLED=true)

### Génération du Realm

```bash
ETABLISSEMENT_CODE=uob \
ETABLISSEMENT_NOM="Université Omar Bongo" \
KC_CENTRAL_URL=https://sso.eigen.ga \
EIGEN_GATEWAY_CLIENT_SECRET=<secret-vault> \
EIGEN_REFERENTIEL_LOCAL_CLIENT_SECRET=<secret-vault> \
./scripts/generer-realm.sh --code uob --nom "Université Omar Bongo"
```

### Fichier .env (UOB)

```env
ETABLISSEMENT_CODE=uob
ETABLISSEMENT_NOM=Université Omar Bongo
KC_HOSTNAME=sso.uob.eigen.ga
KC_HTTP_PORT_EXPOSE=8090
KC_HTTPS_PORT_EXPOSE=8453
KC_DB_NAME=keycloak_local_uob
KC_DB_USER=kc_local_uob_usr
KC_DB_PASSWORD=<vault>
KC_ADMIN_USER=eigen-admin-local
KC_ADMIN_PASSWORD=<vault>
REDIS_PASSWORD=<vault>
KC_CENTRAL_URL=https://sso.eigen.ga
KAFKA_BOOTSTRAP=kafka-central:9092
LDAP_ENABLED=true
LDAP_URL=ldap://ad.uob.ga:389
LDAP_BIND_DN=cn=keycloak-reader,ou=service-accounts,dc=uob,dc=ga
LDAP_BIND_CREDENTIAL=<vault>
LDAP_USERS_DN=ou=personnels,dc=uob,dc=ga
EIGEN_GATEWAY_CLIENT_SECRET=<vault>
EIGEN_REFERENTIEL_LOCAL_CLIENT_SECRET=<vault>
```

### Rôles configurés

| Rôle | Description | Permission codes |
|------|-------------|-----------------|
| `etudiant.base` | Tout étudiant actif | scolarite.dossier.consulter, notes.bulletin.consulter, bibliotheque.emprunter, emploidutemps.consulter |
| `etudiant.l1` | Licence 1 (composite de etudiant.base) | + scolarite.planning.consulter |
| `enseignant.titulaire` | Enseignant titulaire | notes.bulletin.saisir, notes.grille.modifier, scolarite.liste_etudiants.consulter |
| `personnel.admin.scolarite` | Scolarité | scolarite.*, notes.bulletin.consulter |
| `admin.etablissement` | Admin UOB | permissions complètes |

### Endpoints JWKS

```
https://sso.uob.eigen.ga/realms/realm-uob/protocol/openid-connect/certs
```

Le Gateway Master UOB télécharge cette clé au démarrage pour valider tous les tokens.
