#!/usr/bin/env bash
# =============================================================================
# EIGEN IAM — Script d'initialisation Keycloak Local
# Usage : ./scripts/init-keycloak-local.sh
#
# Configure les éléments post-démarrage :
#   - Secrets des service accounts
#   - User Federation LDAP/AD (si LDAP_ENABLED=true)
#   - Vérification de l'Identity Provider vers Keycloak Central
#   - Attribution des rôles admin aux service accounts
# =============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_success() { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
log_section() { echo -e "\n${BOLD}=== $* ===${NC}\n"; }

# --- Configuration ---
KC_BASE_URL="${KC_BASE_URL:-http://localhost:8090}"
KC_ADMIN_USER="${KC_ADMIN_USER:-eigen-admin-local}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:?KC_ADMIN_PASSWORD obligatoire}"
ETABLISSEMENT_CODE="${ETABLISSEMENT_CODE:?ETABLISSEMENT_CODE obligatoire}"
TARGET_REALM="realm-${ETABLISSEMENT_CODE}"

KCADM="kcadm.sh"
[ -f "/opt/keycloak/bin/kcadm.sh" ] && KCADM="/opt/keycloak/bin/kcadm.sh"

log_section "EIGEN IAM :: Initialisation Keycloak Local — ${ETABLISSEMENT_CODE}"

# --- Attente Keycloak ---
log_info "Attente que Keycloak Local soit prêt..."
MAX_WAIT=120; WAITED=0
until curl -sf "$KC_BASE_URL/health/ready" > /dev/null 2>&1; do
    [ $WAITED -ge $MAX_WAIT ] && { log_error "Timeout. Abandon."; exit 1; }
    sleep 3; WAITED=$((WAITED + 3)); echo -n "."
done
echo ""; log_success "Keycloak Local prêt ✓"

# --- Auth admin ---
$KCADM config credentials \
    --server "$KC_BASE_URL" --realm master \
    --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" --client admin-cli
log_success "Authentifié ✓"

# --- Vérification du realm ---
log_section "Vérification du Realm $TARGET_REALM"
if $KCADM get realms/"$TARGET_REALM" > /dev/null 2>&1; then
    log_success "Realm $TARGET_REALM accessible ✓"
else
    log_error "Realm $TARGET_REALM non trouvé. Vérifier l'import."
    exit 1
fi

# --- Secrets des clients ---
log_section "Configuration des Secrets Clients"

for CLIENT_PAIR in \
    "gateway-${ETABLISSEMENT_CODE}:${EIGEN_GATEWAY_CLIENT_SECRET:-}" \
    "eigen-referentiel-local:${EIGEN_REFERENTIEL_LOCAL_CLIENT_SECRET:-}"; do

    CLIENT_ID="${CLIENT_PAIR%%:*}"
    SECRET="${CLIENT_PAIR##*:}"

    if [ -z "$SECRET" ]; then
        log_warn "Secret non défini pour $CLIENT_ID — ignoré"
        continue
    fi

    KC_CLIENT_UUID=$($KCADM get clients -r "$TARGET_REALM" --fields id,clientId \
        | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((x['id'] for x in d if x.get('clientId')=='${CLIENT_ID}'), ''))" 2>/dev/null || true)

    if [ -n "$KC_CLIENT_UUID" ]; then
        $KCADM update "clients/$KC_CLIENT_UUID" -r "$TARGET_REALM" -s "secret=$SECRET"
        log_success "Secret configuré pour $CLIENT_ID ✓"
    else
        log_warn "Client $CLIENT_ID non trouvé dans $TARGET_REALM"
    fi
done

# --- Rôles du service account Référentiel ---
log_section "Attribution des rôles au service account Référentiel"

# Le Référentiel doit pouvoir gérer les utilisateurs dans Keycloak Local
$KCADM add-roles \
    -r "$TARGET_REALM" \
    --uusername "service-account-eigen-referentiel-local" \
    --cclientid realm-management \
    --rolename manage-users 2>/dev/null || log_warn "Rôle manage-users déjà assigné ou non trouvé"

$KCADM add-roles \
    -r "$TARGET_REALM" \
    --uusername "service-account-eigen-referentiel-local" \
    --cclientid realm-management \
    --rolename view-users 2>/dev/null || true

$KCADM add-roles \
    -r "$TARGET_REALM" \
    --uusername "service-account-eigen-referentiel-local" \
    --cclientid realm-management \
    --rolename manage-realm 2>/dev/null || true

log_success "Rôles service account Référentiel configurés ✓"

# --- Configuration LDAP/AD (si activé) ---
if [ "${LDAP_ENABLED:-false}" = "true" ]; then
    log_section "Configuration User Federation LDAP/AD"

    if [ -z "${LDAP_URL:-}" ] || [ -z "${LDAP_BIND_DN:-}" ] || [ -z "${LDAP_USERS_DN:-}" ]; then
        log_error "Variables LDAP_URL, LDAP_BIND_DN et LDAP_USERS_DN sont requises quand LDAP_ENABLED=true"
        exit 1
    fi

    log_info "Configuration LDAP : $LDAP_URL"

    # Créer la User Federation LDAP
    LDAP_ID=$($KCADM create components -r "$TARGET_REALM" \
        -s "name=eigen-ad-${ETABLISSEMENT_CODE}" \
        -s "providerId=ldap" \
        -s "providerType=org.keycloak.storage.UserStorageProvider" \
        -s "config.vendor=[\"ad\"]" \
        -s "config.connectionUrl=[\"${LDAP_URL}\"]" \
        -s "config.bindDn=[\"${LDAP_BIND_DN}\"]" \
        -s "config.bindCredential=[\"${LDAP_BIND_CREDENTIAL:-}\"]" \
        -s "config.usersDn=[\"${LDAP_USERS_DN}\"]" \
        -s "config.usernameLDAPAttribute=[\"sAMAccountName\"]" \
        -s "config.rdnLDAPAttribute=[\"cn\"]" \
        -s "config.uuidLDAPAttribute=[\"objectGUID\"]" \
        -s "config.userObjectClasses=[\"person, organizationalPerson, user\"]" \
        -s "config.authType=[\"simple\"]" \
        -s "config.searchScope=[\"2\"]" \
        -s "config.useTruststoreSpi=[\"ldapsOnly\"]" \
        -s "config.connectionPooling=[\"true\"]" \
        -s "config.pagination=[\"true\"]" \
        -s "config.editMode=[\"READ_ONLY\"]" \
        -s "config.syncRegistrations=[\"false\"]" \
        -s "config.fullSyncPeriod=[-1]" \
        -s "config.changedSyncPeriod=[600]" \
        -s "config.enabled=[\"true\"]" \
        -s "config.priority=[\"0\"]" \
        2>&1 | grep "^id=" | cut -d= -f2 || true)

    if [ -n "$LDAP_ID" ]; then
        log_success "User Federation LDAP créée (id=$LDAP_ID) ✓"

        # Attribute Mappers LDAP → Keycloak
        for MAPPER in \
            "givenName:given_name:firstName:true" \
            "sn:family_name:lastName:true" \
            "mail:email:email:true" \
            "employeeID:identifiant_national:identifiant_national:false" \
            "department:departement_ad:departement_ad:false"; do

            LDAP_ATTR="${MAPPER%%:*}"; REST="${MAPPER#*:}"
            KC_ATTR="${REST%%:*}"; REST="${REST#*:}"
            MODEL_ATTR="${REST%%:*}"; MANDATORY="${REST##*:}"

            $KCADM create components -r "$TARGET_REALM" \
                -s "name=mapper-${LDAP_ATTR}" \
                -s "providerId=user-attribute-ldap-mapper" \
                -s "providerType=org.keycloak.storage.ldap.mappers.LDAPStorageMapper" \
                -s "parentId=$LDAP_ID" \
                -s "config.ldap.attribute=[\"${LDAP_ATTR}\"]" \
                -s "config.user.model.attribute=[\"${MODEL_ATTR}\"]" \
                -s "config.is.mandatory.in.ldap=[\"${MANDATORY}\"]" \
                -s "config.read.only=[\"true\"]" \
                -s "config.always.read.value.from.ldap=[\"true\"]" 2>/dev/null || \
                log_warn "Mapper $LDAP_ATTR déjà existant"
        done

        log_success "Attribute Mappers LDAP configurés ✓"

        # Synchronisation initiale
        log_info "Déclenchement de la synchronisation LDAP initiale..."
        $KCADM create "user-storage/$LDAP_ID/sync?action=triggerFullSync" -r "$TARGET_REALM" 2>/dev/null || \
            log_warn "Sync initiale non déclenchée (peut-être LDAP non accessible depuis le conteneur)"
    else
        log_warn "Création de la User Federation LDAP échouée — vérifier les paramètres LDAP"
    fi
fi

# --- Vérification Identity Provider ---
log_section "Vérification Identity Provider Keycloak Central"

IDP_STATUS=$($KCADM get identity-provider/instances -r "$TARGET_REALM" --fields alias,enabled \
    | python3 -c "import json,sys; d=json.load(sys.stdin); idp=next((x for x in d if x.get('alias')=='keycloak-central-eigen'),None); print(idp.get('enabled','?') if idp else 'NOT_FOUND')" 2>/dev/null || echo "ERROR")

if [ "$IDP_STATUS" = "true" ]; then
    log_success "Identity Provider keycloak-central-eigen activé ✓"
elif [ "$IDP_STATUS" = "NOT_FOUND" ]; then
    log_warn "Identity Provider keycloak-central-eigen non trouvé — vérifier le realm JSON importé"
else
    log_warn "Identity Provider status: $IDP_STATUS"
fi

# --- Résumé ---
log_section "Résumé"
echo -e "${GREEN}✓${NC} Keycloak Local EIGEN initialisé — ${ETABLISSEMENT_CODE}"
echo ""
echo "  Realm       : $TARGET_REALM"
echo "  URL         : $KC_BASE_URL"
echo "  OIDC Config : $KC_BASE_URL/realms/$TARGET_REALM/.well-known/openid-configuration"
echo "  JWKS        : $KC_BASE_URL/realms/$TARGET_REALM/protocol/openid-connect/certs"
echo "  Admin UI    : $KC_BASE_URL/admin/master/console/#/$TARGET_REALM"
echo ""
log_success "Initialisation terminée ✓"
