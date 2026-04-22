package ga.eigen.keycloak.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Instant;
import java.util.*;

/**
 * EIGEN IAM — Keycloak Session Manager Redis
 *
 * <p>Ce SPI est le pont entre les sessions Keycloak et Redis.
 * Il garantit que le Gateway Master peut vérifier en O(1) si une session
 * est encore valide, sans appeler Keycloak pour chaque requête.
 *
 * <h2>Format des clés Redis</h2>
 * <pre>
 * session:{etablissement_code}:{keycloak_user_id}
 *   → valeur JSON avec métadonnées de session
 *   → TTL = sessionTtlSeconds (par défaut 86400s = 24h)
 * </pre>
 *
 * <h2>Événements gérés</h2>
 * <ul>
 *   <li>LOGIN réussi → SET session:... EX ttl</li>
 *   <li>LOGOUT → DEL session:...</li>
 *   <li>Admin USER UPDATE (disabled) → DEL session:... (révocation immédiate)</li>
 *   <li>REFRESH_TOKEN réussi → EXPIRE session:... (renouvelle le TTL)</li>
 * </ul>
 *
 * @author EIGEN IAM Team
 * @version 1.0.0
 */
public class EigenSessionManager implements EventListenerProvider {

    private static final Logger log = LoggerFactory.getLogger(EigenSessionManager.class);

    /** Préfixe des clés Redis EIGEN — permet de cohabiter avec d'autres systèmes */
    private static final String REDIS_KEY_PREFIX = "eigen:session:";

    private final KeycloakSession session;
    private final JedisPool jedisPool;
    private final long sessionTtlSeconds;
    private final String etablissementCode;
    private final ObjectMapper mapper;

    public EigenSessionManager(
            KeycloakSession session,
            JedisPool jedisPool,
            long sessionTtlSeconds,
            String etablissementCode
    ) {
        this.session = session;
        this.jedisPool = jedisPool;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.etablissementCode = etablissementCode;
        this.mapper = new ObjectMapper()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // =========================================================================
    // Événements utilisateur
    // =========================================================================

    @Override
    public void onEvent(Event event) {
        try {
            switch (event.getType()) {
                case LOGIN -> gererLogin(event);
                case LOGOUT -> gererLogout(event);
                case REFRESH_TOKEN -> gererRefresh(event);
                case LOGIN_ERROR -> { /* pas d'action Redis pour les échecs */ }
                default -> { /* ignoré */ }
            }
        } catch (Exception e) {
            log.error("EIGEN-Redis :: Erreur lors du traitement de l'événement {} pour userId={} : {}",
                    event.getType(), event.getUserId(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Événements admin
    // =========================================================================

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        try {
            // Désactivation d'un compte via l'API Admin → révocation immédiate de session
            if (adminEvent.getResourceType() == ResourceType.USER &&
                adminEvent.getOperationType() == OperationType.UPDATE) {

                String userId = extraireUserId(adminEvent.getResourcePath());
                if (userId != null && compteEstDesactive(adminEvent.getRepresentation())) {
                    revoquerSession(userId, "compte_desactive_admin");
                }
            }

            // Suppression d'un compte
            if (adminEvent.getResourceType() == ResourceType.USER &&
                adminEvent.getOperationType() == OperationType.DELETE) {

                String userId = extraireUserId(adminEvent.getResourcePath());
                if (userId != null) {
                    revoquerSession(userId, "compte_supprime");
                }
            }

        } catch (Exception e) {
            log.error("EIGEN-Redis :: Erreur lors du traitement de l'événement admin : {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // Logique de gestion des sessions Redis
    // =========================================================================

    /**
     * Connexion réussie → créer la session dans Redis.
     *
     * <p>La clé Redis est basée sur le userId Keycloak (le sub du token local).
     * C'est exactement ce que le Gateway utilise pour vérifier la session.
     */
    private void gererLogin(Event event) {
        String userId = event.getUserId();
        if (userId == null) return;

        String cleRedis = construireCle(userId);

        // Construire le payload de session stocké dans Redis
        Map<String, Object> sessionData = new LinkedHashMap<>();
        sessionData.put("user_id", userId);
        sessionData.put("session_id", event.getSessionId());
        sessionData.put("realm_id", event.getRealmId());
        sessionData.put("client_id", event.getClientId());
        sessionData.put("ip_address", event.getIpAddress());
        sessionData.put("etablissement_code", etablissementCode);
        sessionData.put("created_at", Instant.now().toString());
        sessionData.put("expires_at", Instant.now().plusSeconds(sessionTtlSeconds).toString());

        // Enrichir avec les attributs du compte
        enrichirAvecAttributsUtilisateur(sessionData, event.getRealmId(), userId);

        try {
            String json = mapper.writeValueAsString(sessionData);
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.set(cleRedis, json, SetParams.setParams().ex(sessionTtlSeconds));
            }
            log.info("EIGEN-Redis :: Session créée → {} (TTL={}s)", cleRedis, sessionTtlSeconds);
        } catch (Exception e) {
            log.error("EIGEN-Redis :: Impossible d'écrire la session {} dans Redis : {}", cleRedis, e.getMessage());
        }
    }

    /**
     * Déconnexion → supprimer la session de Redis.
     * Effet immédiat : les prochaines requêtes du Gateway seront rejetées.
     */
    private void gererLogout(Event event) {
        if (event.getUserId() == null) return;
        revoquerSession(event.getUserId(), "logout_utilisateur");
    }

    /**
     * Refresh token → renouveler le TTL de la session dans Redis.
     * Permet de garder la session active tant que l'utilisateur est actif.
     */
    private void gererRefresh(Event event) {
        if (event.getUserId() == null) return;

        String cleRedis = construireCle(event.getUserId());
        try (Jedis jedis = jedisPool.getResource()) {
            long resultat = jedis.expire(cleRedis, sessionTtlSeconds);
            if (resultat == 1L) {
                log.debug("EIGEN-Redis :: TTL renouvelé → {} ({}s)", cleRedis, sessionTtlSeconds);
            } else {
                // La clé n'existait plus — recréer la session si possible
                log.warn("EIGEN-Redis :: Session {} expirée avant le refresh — recréation nécessaire", cleRedis);
                // On laisse le Gateway gérer le 401 — le client refera une authentification complète
            }
        } catch (Exception e) {
            log.error("EIGEN-Redis :: Impossible de renouveler le TTL de {} : {}", cleRedis, e.getMessage());
        }
    }

    /**
     * Révocation de session — supprime la clé Redis.
     * Utilisé par LOGOUT et désactivation admin.
     */
    private void revoquerSession(String userId, String raison) {
        String cleRedis = construireCle(userId);
        try (Jedis jedis = jedisPool.getResource()) {
            long deleted = jedis.del(cleRedis);
            if (deleted > 0) {
                log.info("EIGEN-Redis :: Session révoquée → {} (raison={})", cleRedis, raison);
            } else {
                log.debug("EIGEN-Redis :: Clé {} non trouvée lors de la révocation (déjà expirée ?)", cleRedis);
            }
        } catch (Exception e) {
            log.error("EIGEN-Redis :: Impossible de révoquer la session {} : {}", cleRedis, e.getMessage());
        }
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    /**
     * Construit la clé Redis pour un profil utilisateur.
     * Format : eigen:session:{etablissement_code}:{user_id}
     *
     * <p>Ce format permet de :
     * - Lister toutes les sessions d'un établissement : KEYS eigen:session:uob:*
     * - Éviter les collisions entre établissements
     * - Scanner et expirer en masse si besoin (SCAN + TTL)
     */
    private String construireCle(String userId) {
        return REDIS_KEY_PREFIX + etablissementCode.toLowerCase() + ":" + userId;
    }

    /**
     * Enrichit les données de session avec les attributs du compte Keycloak.
     */
    private void enrichirAvecAttributsUtilisateur(Map<String, Object> data, String realmId, String userId) {
        try {
            RealmModel realm = session.realms().getRealm(realmId);
            if (realm == null) return;
            UserModel user = session.users().getUserById(realm, userId);
            if (user == null) return;

            getAttr(user, "compte_referentiel_id").ifPresent(v -> data.put("compte_referentiel_id", v));
            getAttr(user, "user_id_national").ifPresent(v -> data.put("user_id_national", v));
            getAttr(user, "identifiant_national").ifPresent(v -> data.put("identifiant_national", v));
            getAttr(user, "type_profil").ifPresent(v -> data.put("type_profil", v));
            getAttr(user, "filiere").ifPresent(v -> data.put("filiere", v));
            getAttr(user, "etablissement_code").ifPresent(v -> data.put("etablissement_code_profil", v));

        } catch (Exception e) {
            log.warn("EIGEN-Redis :: Impossible d'enrichir les données de session pour {} : {}", userId, e.getMessage());
        }
    }

    private Optional<String> getAttr(UserModel user, String nom) {
        List<String> vals = user.getAttribute(nom);
        return (vals != null && !vals.isEmpty()) ? Optional.ofNullable(vals.get(0)) : Optional.empty();
    }

    private String extraireUserId(String resourcePath) {
        if (resourcePath == null) return null;
        String[] parts = resourcePath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("users".equals(parts[i])) return parts[i + 1];
        }
        return null;
    }

    /**
     * Vérifie si la représentation JSON d'un UPDATE admin indique une désactivation.
     */
    private boolean compteEstDesactive(String representation) {
        if (representation == null) return false;
        try {
            Map<?, ?> data = mapper.readValue(representation, Map.class);
            Object enabled = data.get("enabled");
            return Boolean.FALSE.equals(enabled);
        } catch (Exception e) {
            // En cas de doute, on ne révoque pas
            return false;
        }
    }

    @Override
    public void close() {
        // Pool géré par la Factory
    }
}
