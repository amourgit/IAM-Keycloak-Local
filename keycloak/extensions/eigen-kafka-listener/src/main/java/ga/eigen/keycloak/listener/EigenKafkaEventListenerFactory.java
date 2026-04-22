package ga.eigen.keycloak.listener;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EIGEN IAM — Factory du Keycloak Event Listener Kafka
 *
 * <p>Cette Factory est le point d'entrée du SPI. Elle est instanciée une seule
 * fois par Keycloak au démarrage et gère le cycle de vie du KafkaProducer partagé.
 *
 * <p>Configuration via variables d'environnement :
 * <ul>
 *   <li>{@code EIGEN_KAFKA_BOOTSTRAP} : adresses des brokers Kafka (ex: kafka:9092)</li>
 *   <li>{@code EIGEN_KAFKA_TOPIC_PREFIX} : préfixe des topics (ex: eigen.national)</li>
 *   <li>{@code EIGEN_ETABLISSEMENT_CODE} : code établissement (NATIONAL ou UOB, ESGA...)</li>
 * </ul>
 *
 * @author EIGEN IAM Team
 * @version 1.0.0
 */
public class EigenKafkaEventListenerFactory implements EventListenerProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(EigenKafkaEventListenerFactory.class);

    /** Identifiant du provider — doit correspondre exactement à eventsListeners dans le realm JSON */
    public static final String PROVIDER_ID = "eigen-kafka-event-listener";

    private KafkaProducer<String, String> sharedProducer;
    private String topicPrefix;
    private String etablissementCode;
    private final AtomicBoolean initialise = new AtomicBoolean(false);

    // =========================================================================
    // Cycle de vie — appelé par Keycloak au démarrage
    // =========================================================================

    @Override
    public void init(Config.Scope config) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  EIGEN IAM :: Initialisation du Kafka Event Listener         ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // Lecture de la configuration depuis les variables d'environnement
        String bootstrapServers = lireConfig("EIGEN_KAFKA_BOOTSTRAP", "localhost:9092");
        topicPrefix = lireConfig("EIGEN_KAFKA_TOPIC_PREFIX", "eigen.national");
        etablissementCode = lireConfig("EIGEN_ETABLISSEMENT_CODE", "NATIONAL");

        log.info("EIGEN-Kafka :: Bootstrap servers : {}", bootstrapServers);
        log.info("EIGEN-Kafka :: Topic prefix      : {}", topicPrefix);
        log.info("EIGEN-Kafka :: Établissement     : {}", etablissementCode);

        try {
            Properties props = buildKafkaProducerProperties(bootstrapServers);
            this.sharedProducer = new KafkaProducer<>(props);

            // Vérification de la connexion Kafka
            this.sharedProducer.partitionsFor(topicPrefix + ".audit");

            initialise.set(true);
            log.info("EIGEN-Kafka :: KafkaProducer initialisé avec succès ✓");
        } catch (Exception e) {
            log.error("EIGEN-Kafka :: ATTENTION — Impossible de se connecter à Kafka : {}", e.getMessage());
            log.error("EIGEN-Kafka :: Le listener continuera de démarrer en mode dégradé.");
            log.error("EIGEN-Kafka :: Les événements Keycloak ne seront PAS publiés sur Kafka tant que Kafka est indisponible.");
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        log.info("EIGEN-Kafka :: Post-initialisation terminée. Listener actif.");
    }

    @Override
    public void close() {
        log.info("EIGEN-Kafka :: Arrêt du KafkaProducer...");
        if (sharedProducer != null) {
            try {
                sharedProducer.flush();
                sharedProducer.close();
                log.info("EIGEN-Kafka :: KafkaProducer arrêté proprement ✓");
            } catch (Exception e) {
                log.error("EIGEN-Kafka :: Erreur lors de l'arrêt du KafkaProducer : {}", e.getMessage());
            }
        }
    }

    // =========================================================================
    // Création des instances de listener
    // =========================================================================

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        if (!initialise.get() || sharedProducer == null) {
            // Mode dégradé : retourne un listener no-op qui loggue les événements
            log.warn("EIGEN-Kafka :: Kafka non disponible — retour vers le listener no-op");
            return new EigenKafkaEventListenerNoOp(topicPrefix, etablissementCode);
        }
        return new EigenKafkaEventListener(session, sharedProducer, topicPrefix, etablissementCode);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    // =========================================================================
    // Configuration du KafkaProducer
    // =========================================================================

    /**
     * Construit les propriétés du KafkaProducer optimisées pour EIGEN.
     *
     * <p>Configuration orientée fiabilité et performance :
     * <ul>
     *   <li>acks=1 : confirmation de l'écriture par le leader (compromis fiabilité/latence)</li>
     *   <li>retries=3 : 3 tentatives en cas d'erreur transitoire</li>
     *   <li>batch.size=16384 : regroupement des messages pour efficacité réseau</li>
     *   <li>linger.ms=5 : attente courte pour remplir les batches</li>
     *   <li>compression.type=lz4 : compression efficace pour JSON</li>
     * </ul>
     */
    private Properties buildKafkaProducerProperties(String bootstrapServers) {
        Properties props = new Properties();

        // Connexion
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Identification du producer
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "keycloak-eigen-" + etablissementCode.toLowerCase());

        // Fiabilité
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "500");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");

        // Performance
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432"); // 32MB

        // Compression JSON → LZ4 (meilleur ratio pour JSON)
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // Idempotence — évite les doublons en cas de retry réseau
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");

        return props;
    }

    /**
     * Lit une valeur de configuration depuis les variables d'environnement.
     * Priorité : variable d'environnement > valeur par défaut.
     */
    private String lireConfig(String envVar, String valeurParDefaut) {
        String valeur = System.getenv(envVar);
        if (valeur == null || valeur.isBlank()) {
            log.warn("EIGEN-Kafka :: Variable {} non définie, utilisation de la valeur par défaut : {}",
                    envVar, valeurParDefaut);
            return valeurParDefaut;
        }
        return valeur.trim();
    }
}
