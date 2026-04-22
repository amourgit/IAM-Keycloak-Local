package ga.eigen.keycloak.listener;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * EIGEN IAM — Keycloak Kafka Event Listener
 *
 * <p>SPI Keycloak qui intercepte tous les événements d'authentification et
 * d'administration et les publie sur les topics Kafka EIGEN correspondants.
 *
 * <p>Cette classe est instanciée par Keycloak pour chaque événement.
 * Elle est thread-safe car le KafkaProducer partagé est thread-safe.
 *
 * <p>Mapping des événements vers les topics :
 * <ul>
 *   <li>LOGIN / LOGIN_ERROR → eigen.{niveau}.session.ouverte / eigen.{niveau}.authentification.echec</li>
 *   <li>LOGOUT → eigen.{niveau}.session.fermee</li>
 *   <li>Admin USER CREATE → eigen.{niveau}.compte.cree</li>
 *   <li>Admin USER UPDATE → eigen.{niveau}.compte.modifie</li>
 *   <li>Admin USER DELETE → eigen.{niveau}.compte.desactive</li>
 * </ul>
 *
 * @author EIGEN IAM Team
 * @version 1.0.0
 */
public class EigenKafkaEventListener implements EventListenerProvider {

    private static final Logger log = LoggerFactory.getLogger(EigenKafkaEventListener.class);

    private final KeycloakSession session;
    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final String topicPrefix;
    private final String etablissementCode;
    private final String niveau; // "national" pour Central, code établissement pour Local

    /**
     * Constructeur appelé par la Factory pour chaque session Keycloak.
     */
    public EigenKafkaEventListener(
            KeycloakSession session,
            KafkaProducer<String, String> sharedProducer,
            String topicPrefix,
            String etablissementCode
    ) {
        this.session = session;
        this.producer = sharedProducer;
        this.mapper = buildObjectMapper();
        this.topicPrefix = topicPrefix;
        this.etablissementCode = etablissementCode;
        this.niveau = "NATIONAL".equals(etablissementCode) ? "national" : "local";
    }

    // =========================================================================
    // Événements d'authentification (utilisateurs finaux)
    // =========================================================================

    @Override
    public void onEvent(Event event) {
        try {
            switch (event.getType()) {
                case LOGIN -> publierEvenementSession(event, "ouverte");
                case LOGIN_ERROR -> publierEvenementEchec(event);
                case LOGOUT -> publierEvenementSession(event, "fermee");
                case REFRESH_TOKEN -> { /* pas de publication Kafka pour les refresh — trop fréquents */ }
                case UPDATE_PASSWORD -> publierEvenementAudit(event, "mot_de_passe_modifie");
                case VERIFY_EMAIL -> publierEvenementAudit(event, "email_verifie");
                case IDENTITY_PROVIDER_FIRST_LOGIN -> publierEvenementAudit(event, "premiere_connexion_sso");
                default -> {
                    if (log.isTraceEnabled()) {
                        log.trace("EIGEN-Kafka :: Événement ignoré : {}", event.getType());
                    }
                }
            }
        } catch (Exception e) {
            log.error("EIGEN-Kafka :: Erreur lors du traitement de l'événement {} : {}",
                    event.getType(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Événements d'administration (API Admin Keycloak)
    // =========================================================================

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        try {
            if (adminEvent.getResourceType() == ResourceType.USER) {
                switch (adminEvent.getOperationType()) {
                    case CREATE -> publierEvenementAdminCompte(adminEvent, "compte.cree", includeRepresentation);
                    case UPDATE -> publierEvenementAdminCompte(adminEvent, "compte.modifie", includeRepresentation);
                    case DELETE -> publierEvenementAdminCompte(adminEvent, "compte.desactive", includeRepresentation);
                    default -> {}
                }
            } else if (adminEvent.getResourceType() == ResourceType.REALM_ROLE_MAPPING) {
                publierEvenementAdminRoles(adminEvent, includeRepresentation);
            } else if (adminEvent.getResourceType() == ResourceType.GROUP_MEMBERSHIP) {
                publierEvenementAdminGroupe(adminEvent, includeRepresentation);
            }
        } catch (Exception e) {
            log.error("EIGEN-Kafka :: Erreur lors du traitement de l'événement admin {} {} : {}",
                    adminEvent.getResourceType(), adminEvent.getOperationType(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Méthodes de publication
    // =========================================================================

    /**
     * Publie un événement de session (ouverture / fermeture).
     */
    private void publierEvenementSession(Event event, String action) {
        String topic = String.format("%s.session.%s", topicPrefix, action);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "session_" + action);
        payload.put("user_id", event.getUserId());
        payload.put("session_id", event.getSessionId());
        payload.put("realm_id", event.getRealmId());
        payload.put("client_id", event.getClientId());
        payload.put("ip_address", event.getIpAddress());
        payload.put("etablissement_code", etablissementCode);
        payload.put("horodatage", Instant.ofEpochMilli(event.getTime()).toString());

        // Enrichissement avec les attributs utilisateur si disponible
        if (event.getUserId() != null) {
            enrichirAvecAttributsUtilisateur(payload, event.getRealmId(), event.getUserId());
        }

        publier(topic, event.getUserId(), payload);
    }

    /**
     * Publie un événement d'échec d'authentification.
     */
    private void publierEvenementEchec(Event event) {
        String topic = String.format("%s.authentification.echec", topicPrefix);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "authentification_echec");
        payload.put("user_id", event.getUserId());
        payload.put("realm_id", event.getRealmId());
        payload.put("client_id", event.getClientId());
        payload.put("ip_address", event.getIpAddress());
        payload.put("etablissement_code", etablissementCode);
        payload.put("erreur", event.getError());
        payload.put("details", event.getDetails());
        payload.put("horodatage", Instant.ofEpochMilli(event.getTime()).toString());

        // Clé de partition = IP pour détecter les brute-force par IP
        String partitionKey = event.getIpAddress() != null ? event.getIpAddress() : event.getUserId();
        publier(topic, partitionKey, payload);
    }

    /**
     * Publie un événement d'audit générique.
     */
    private void publierEvenementAudit(Event event, String typeAction) {
        String topic = String.format("%s.audit", topicPrefix);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", typeAction);
        payload.put("user_id", event.getUserId());
        payload.put("session_id", event.getSessionId());
        payload.put("realm_id", event.getRealmId());
        payload.put("client_id", event.getClientId());
        payload.put("ip_address", event.getIpAddress());
        payload.put("etablissement_code", etablissementCode);
        payload.put("details", event.getDetails());
        payload.put("horodatage", Instant.ofEpochMilli(event.getTime()).toString());

        publier(topic, event.getUserId(), payload);
    }

    /**
     * Publie un événement de gestion de compte (create/update/delete via API Admin).
     */
    private void publierEvenementAdminCompte(AdminEvent adminEvent, String suffixTopic, boolean includeRepresentation) {
        String topic = String.format("%s.%s", topicPrefix, suffixTopic);

        // Extraire l'ID utilisateur depuis le resourcePath (format: "users/{userId}")
        String userId = extraireUserId(adminEvent.getResourcePath());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", suffixTopic.replace(".", "_"));
        payload.put("user_id", userId);
        payload.put("realm_id", adminEvent.getRealmId());
        payload.put("acteur_id", adminEvent.getAuthDetails() != null ? adminEvent.getAuthDetails().getUserId() : null);
        payload.put("acteur_ip", adminEvent.getAuthDetails() != null ? adminEvent.getAuthDetails().getIpAddress() : null);
        payload.put("etablissement_code", etablissementCode);
        payload.put("horodatage", Instant.ofEpochMilli(adminEvent.getTime()).toString());

        // La représentation JSON contient les données du compte modifié
        if (includeRepresentation && adminEvent.getRepresentation() != null) {
            try {
                Object representation = mapper.readValue(adminEvent.getRepresentation(), Object.class);
                payload.put("representation", representation);
            } catch (Exception e) {
                payload.put("representation_brute", adminEvent.getRepresentation());
                log.warn("EIGEN-Kafka :: Impossible de parser la représentation de l'événement admin", e);
            }
        }

        // Enrichissement avec les attributs utilisateur si disponible
        if (userId != null && adminEvent.getRealmId() != null) {
            enrichirAvecAttributsUtilisateur(payload, adminEvent.getRealmId(), userId);
        }

        publier(topic, userId, payload);
    }

    /**
     * Publie un événement de changement de rôles.
     */
    private void publierEvenementAdminRoles(AdminEvent adminEvent, boolean includeRepresentation) {
        String topic = String.format("%s.audit", topicPrefix);

        // resourcePath format: "users/{userId}/role-mappings/realm"
        String userId = extraireUserId(adminEvent.getResourcePath());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "roles_modifies");
        payload.put("user_id", userId);
        payload.put("operation", adminEvent.getOperationType().toString());
        payload.put("realm_id", adminEvent.getRealmId());
        payload.put("acteur_id", adminEvent.getAuthDetails() != null ? adminEvent.getAuthDetails().getUserId() : null);
        payload.put("etablissement_code", etablissementCode);
        payload.put("horodatage", Instant.ofEpochMilli(adminEvent.getTime()).toString());

        if (includeRepresentation && adminEvent.getRepresentation() != null) {
            try {
                Object roles = mapper.readValue(adminEvent.getRepresentation(), Object.class);
                payload.put("roles", roles);
            } catch (Exception e) {
                log.warn("EIGEN-Kafka :: Impossible de parser les rôles", e);
            }
        }

        publier(topic, userId, payload);
    }

    /**
     * Publie un événement de changement de groupe.
     */
    private void publierEvenementAdminGroupe(AdminEvent adminEvent, boolean includeRepresentation) {
        String topic = String.format("%s.audit", topicPrefix);
        String userId = extraireUserId(adminEvent.getResourcePath());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "groupe_modifie");
        payload.put("user_id", userId);
        payload.put("operation", adminEvent.getOperationType().toString());
        payload.put("realm_id", adminEvent.getRealmId());
        payload.put("etablissement_code", etablissementCode);
        payload.put("horodatage", Instant.ofEpochMilli(adminEvent.getTime()).toString());

        publier(topic, userId, payload);
    }

    // =========================================================================
    // Méthodes utilitaires
    // =========================================================================

    /**
     * Enrichit le payload avec les attributs customs de l'utilisateur depuis Keycloak.
     * Permet aux consommateurs Kafka d'avoir l'identifiant_national sans appel supplémentaire.
     */
    private void enrichirAvecAttributsUtilisateur(Map<String, Object> payload, String realmId, String userId) {
        try {
            RealmModel realm = session.realms().getRealm(realmId);
            if (realm == null) return;

            UserModel user = session.users().getUserById(realm, userId);
            if (user == null) return;

            // Attributs standards
            payload.put("email", user.getEmail());
            payload.put("nom", user.getLastName());
            payload.put("prenom", user.getFirstName());

            // Attributs EIGEN custom
            getAttribut(user, "identifiant_national").ifPresent(v -> payload.put("identifiant_national", v));
            getAttribut(user, "type_utilisateur").ifPresent(v -> payload.put("type_utilisateur", v));
            getAttribut(user, "type_profil").ifPresent(v -> payload.put("type_profil", v));
            getAttribut(user, "compte_referentiel_id").ifPresent(v -> payload.put("compte_referentiel_id", v));
            getAttribut(user, "user_id_national").ifPresent(v -> payload.put("user_id_national", v));
            getAttribut(user, "filiere").ifPresent(v -> payload.put("filiere", v));
            getAttribut(user, "composante").ifPresent(v -> payload.put("composante", v));
            getAttribut(user, "annee_academique").ifPresent(v -> payload.put("annee_academique", v));

        } catch (Exception e) {
            log.warn("EIGEN-Kafka :: Impossible d'enrichir le payload avec les attributs utilisateur {} : {}",
                    userId, e.getMessage());
        }
    }

    /**
     * Récupère un attribut mono-valué d'un utilisateur Keycloak.
     */
    private Optional<String> getAttribut(UserModel user, String nomAttribut) {
        List<String> valeurs = user.getAttribute(nomAttribut);
        if (valeurs != null && !valeurs.isEmpty()) {
            return Optional.ofNullable(valeurs.get(0));
        }
        return Optional.empty();
    }

    /**
     * Extrait l'ID utilisateur depuis un resourcePath Keycloak Admin.
     * Formats possibles : "users/uuid", "users/uuid/role-mappings/realm", etc.
     */
    private String extraireUserId(String resourcePath) {
        if (resourcePath == null) return null;
        String[] segments = resourcePath.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("users".equals(segments[i])) {
                return segments[i + 1];
            }
        }
        return null;
    }

    /**
     * Sérialise le payload et publie sur le topic Kafka avec retry synchrone.
     * En cas d'échec, loggue l'erreur mais ne propage pas l'exception
     * pour ne pas interrompre le flux Keycloak.
     */
    private void publier(String topic, String partitionKey, Map<String, Object> payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, partitionKey, json);

            // Métadonnées EIGEN dans les headers Kafka
            record.headers()
                .add("eigen.source", "keycloak".getBytes())
                .add("eigen.version", "1.0.0".getBytes())
                .add("eigen.etablissement", etablissementCode.getBytes())
                .add("eigen.niveau", niveau.getBytes())
                .add("eigen.correlation-id", UUID.randomUUID().toString().getBytes());

            Future<RecordMetadata> future = producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("EIGEN-Kafka :: Échec de publication sur le topic {} : {}",
                            topic, exception.getMessage(), exception);
                } else {
                    log.debug("EIGEN-Kafka :: Publié sur {} partition={} offset={}",
                            topic, metadata.partition(), metadata.offset());
                }
            });

            // Attente asynchrone avec timeout court pour ne pas bloquer Keycloak
            future.get(3, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("EIGEN-Kafka :: Erreur critique lors de la publication sur {} : {}",
                    topic, e.getMessage(), e);
            // On ne propage PAS l'exception — Keycloak doit continuer même si Kafka est down
        }
    }

    /**
     * Construit l'ObjectMapper Jackson configuré pour EIGEN.
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return om;
    }

    @Override
    public void close() {
        // Le KafkaProducer est partagé et géré par la Factory — on ne le ferme pas ici
    }
}
