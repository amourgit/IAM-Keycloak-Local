-- =============================================================================
-- EIGEN IAM :: Initialisation PostgreSQL — Keycloak Local
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

CREATE SCHEMA IF NOT EXISTS eigen_audit;

-- Table d'audit locale
CREATE TABLE IF NOT EXISTS eigen_audit.evenements_locaux (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    etablissement   VARCHAR(20) NOT NULL,
    type_evenement  VARCHAR(100) NOT NULL,
    user_id         VARCHAR(36),
    profil_type     VARCHAR(50),
    realm_id        VARCHAR(36),
    client_id       VARCHAR(255),
    ip_address      INET,
    details         JSONB,
    horodatage      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_evt_local_user ON eigen_audit.evenements_locaux (user_id);
CREATE INDEX IF NOT EXISTS idx_evt_local_type ON eigen_audit.evenements_locaux (type_evenement);
CREATE INDEX IF NOT EXISTS idx_evt_local_horodatage ON eigen_audit.evenements_locaux (horodatage DESC);

-- Sessions actives récentes (vue de monitoring)
-- Note : Redis est la source de vérité — cette table est pour l'audit uniquement
CREATE TABLE IF NOT EXISTS eigen_audit.sessions_historique (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    etablissement   VARCHAR(20) NOT NULL,
    user_id         VARCHAR(36) NOT NULL,
    session_id      VARCHAR(36),
    ip_address      INET,
    user_agent      TEXT,
    ouverture       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fermeture       TIMESTAMPTZ,
    raison_fermeture VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_sess_user ON eigen_audit.sessions_historique (user_id);

INSERT INTO eigen_audit.evenements_locaux (etablissement, type_evenement, details)
VALUES (current_database(), 'SYSTEME_INITIALISE',
        '{"message": "Base PostgreSQL Keycloak Local initialisée", "composant": "IAM-Keycloak-Local", "version": "1.0.0"}');
