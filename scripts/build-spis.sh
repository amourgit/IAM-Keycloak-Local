#!/usr/bin/env bash
# =============================================================================
# EIGEN IAM — Build des deux SPIs Keycloak Local
# Produit : eigen-kafka-listener-1.0.0.jar + eigen-session-manager-1.0.0.jar
# Usage   : ./scripts/build-spis.sh [--deploy]
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
EXTENSIONS_DIR="$ROOT_DIR/keycloak/extensions"

log_info()    { echo "[INFO]  $*"; }
log_success() { echo "[OK]    $*"; }
log_error()   { echo "[ERROR] $*" >&2; }

if ! command -v mvn &> /dev/null; then
    log_error "Maven non trouvé."
    exit 1
fi

log_info "=== EIGEN IAM :: Build des SPIs Keycloak Local ==="

for SPI_DIR in "eigen-kafka-listener" "eigen-session-manager"; do
    log_info "Build : $SPI_DIR"
    cd "$EXTENSIONS_DIR/$SPI_DIR"
    mvn clean package -DskipTests -q

    JAR=$(find target -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -1)
    if [ -z "$JAR" ]; then
        log_error "JAR non trouvé pour $SPI_DIR"
        exit 1
    fi

    cp "$JAR" "$EXTENSIONS_DIR/$(basename $JAR)"
    log_success "$SPI_DIR → $EXTENSIONS_DIR/$(basename $JAR) ✓"
done

if [[ "${1:-}" == "--deploy" ]]; then
    CONTAINER="${KC_CONTAINER_NAME:-eigen-keycloak-local-${ETABLISSEMENT_CODE:-uob}}"
    if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
        log_info "Déploiement dans le conteneur $CONTAINER..."
        for JAR in "$EXTENSIONS_DIR"/*.jar; do
            docker cp "$JAR" "$CONTAINER:/opt/keycloak/providers/"
        done
        docker restart "$CONTAINER"
        log_success "SPIs déployés et Keycloak redémarré ✓"
    else
        log_error "Conteneur $CONTAINER non trouvé."
        exit 1
    fi
fi

log_success "Build terminé !"
