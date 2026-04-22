#!/usr/bin/env bash
# =============================================================================
# EIGEN IAM — Génération du Realm JSON par Établissement
#
# Usage : ./scripts/generer-realm.sh --code uob --nom "Université Omar Bongo"
#         ./scripts/generer-realm.sh --code esga --nom "École Supérieure de Gestion et d'Administration"
#         ./scripts/generer-realm.sh --code insg --nom "Institut National des Sciences de Gestion"
#         ./scripts/generer-realm.sh --code ustm --nom "Université des Sciences et Techniques de Masuku"
#
# Produit : keycloak/realm-config/realm-{code}.json
#           prêt pour l'import automatique par Keycloak au démarrage.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
TEMPLATE="$ROOT_DIR/keycloak/realm-config/eigen-local-realm-template.json"
OUTPUT_DIR="$ROOT_DIR/keycloak/realm-config"

# --- Couleurs ---
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_success() { echo -e "${GREEN}[OK]${NC}    $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# --- Parsing des arguments ---
CODE=""
NOM=""
KC_CENTRAL_URL="${KC_CENTRAL_URL:-https://sso.eigen.ga}"
GATEWAY_SECRET="${EIGEN_GATEWAY_CLIENT_SECRET:-CHANGE_ME_IN_PRODUCTION}"
REFERENTIEL_SECRET="${EIGEN_REFERENTIEL_LOCAL_CLIENT_SECRET:-CHANGE_ME_IN_PRODUCTION}"
SMTP_HOST="${SMTP_HOST:-smtp.eigen.ga}"
SMTP_PORT="${SMTP_PORT:-587}"

while [[ $# -gt 0 ]]; do
    case $1 in
        --code) CODE="$2"; shift 2;;
        --nom) NOM="$2"; shift 2;;
        --kc-central-url) KC_CENTRAL_URL="$2"; shift 2;;
        *) log_error "Argument inconnu: $1"; exit 1;;
    esac
done

if [ -z "$CODE" ] || [ -z "$NOM" ]; then
    echo "Usage: $0 --code <code_etablissement> --nom <nom_complet>"
    echo ""
    echo "Exemples :"
    echo "  $0 --code uob --nom 'Université Omar Bongo'"
    echo "  $0 --code esga --nom 'École Supérieure de Gestion et Administration'"
    exit 1
fi

# Vérifier le format du code (minuscules, alphanumériques, tirets)
if ! echo "$CODE" | grep -qE '^[a-z][a-z0-9-]{1,19}$'; then
    log_error "Le code doit être en minuscules, alphanumérique, 2-20 caractères (ex: uob, esga, iut-lb)"
    exit 1
fi

CODE_UPPER=$(echo "$CODE" | tr '[:lower:]' '[:upper:]')
OUTPUT_FILE="$OUTPUT_DIR/realm-${CODE}.json"

log_info "=== EIGEN IAM :: Génération du Realm Local ==="
log_info "Code établissement : $CODE ($CODE_UPPER)"
log_info "Nom               : $NOM"
log_info "KC Central URL    : $KC_CENTRAL_URL"
log_info "Fichier de sortie : $OUTPUT_FILE"
echo ""

# Vérification du template
if [ ! -f "$TEMPLATE" ]; then
    log_error "Template non trouvé : $TEMPLATE"
    exit 1
fi

# --- Génération par substitution ---
sed \
    -e "s|\${ETABLISSEMENT_CODE}|${CODE}|g" \
    -e "s|\${ETABLISSEMENT_CODE_UPPER}|${CODE_UPPER}|g" \
    -e "s|\${ETABLISSEMENT_NOM}|${NOM}|g" \
    -e "s|\${EIGEN_KC_CENTRAL_URL}|${KC_CENTRAL_URL}|g" \
    -e "s|\${EIGEN_GATEWAY_CLIENT_SECRET}|${GATEWAY_SECRET}|g" \
    -e "s|\${EIGEN_REFERENTIEL_LOCAL_CLIENT_SECRET}|${REFERENTIEL_SECRET}|g" \
    -e "s|\${SMTP_HOST}|${SMTP_HOST}|g" \
    -e "s|\${SMTP_PORT:-587}|${SMTP_PORT}|g" \
    "$TEMPLATE" > "$OUTPUT_FILE"

# Validation JSON basique
if command -v python3 &> /dev/null; then
    if python3 -c "import json; json.load(open('$OUTPUT_FILE'))" 2>/dev/null; then
        log_success "JSON valide ✓"
    else
        log_error "JSON invalide — vérifier la substitution"
        exit 1
    fi
fi

log_success "Realm généré : $OUTPUT_FILE"
echo ""
echo -e "${BOLD}Étapes suivantes :${NC}"
echo "  1. Vérifier/ajuster les secrets dans le fichier généré"
echo "  2. Copier ou monter ce fichier dans /opt/keycloak/data/import/"
echo "  3. Démarrer Keycloak avec : docker-compose up -d"
echo "  4. Vérifier l'import : curl http://localhost:8090/realms/realm-${CODE}/.well-known/openid-configuration"
echo ""
echo -e "JWKS endpoint : ${BLUE}http://localhost:8090/realms/realm-${CODE}/protocol/openid-connect/certs${NC}"
