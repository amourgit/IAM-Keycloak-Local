package ga.eigen.keycloak.session;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * EIGEN IAM — Factory du Keycloak Session Manager Redis
 *
 * <p>Gère le cycle de vie du pool de connexions Redis Jedis.
 * Un seul pool est maintenu pour tout le cycle de vie de Keycloak
 * (pattern Singleton via Factory pattern Keycloak SPI).
 *
 * <p>Configuration via variables d'environnement :
 * <ul>
 *   <li>{@code EIGEN_REDIS_HOST} : hôte Redis</li>
 *   <li>{@code EIGEN_REDIS_PORT} : port Redis (défaut 6379)</li>
 *   <li>{@code EIGEN_REDIS_PASSWORD} : mot de passe Redis</li>
 *   <li>{@code EIGEN_SESSION_TTL_SECONDS} : TTL des sessions en Redis (défaut 86400)</li>
 *   <li>{@code EIGEN_ETABLISSEMENT_CODE} : code de l'établissement</li>
 * </ul>
 *
 * @author EIGEN IAM Team
 * @version 1.0.0
 */
public class EigenSessionManagerFactory implements EventListenerProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(EigenSessionManagerFactory.class);
    public static final String PROVIDER_ID = "eigen-session-manager";

    private JedisPool jedisPool;
    private long sessionTtlSeconds;
    private String etablissementCode;
    private boolean redisDisponible = false;

    @Override
    public void init(Config.Scope config) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  EIGEN IAM :: Initialisation du Session Manager Redis         ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        String redisHost     = lireEnv("EIGEN_REDIS_HOST", "localhost");
        int    redisPort     = Integer.parseInt(lireEnv("EIGEN_REDIS_PORT", "6379"));
        String redisPassword = lireEnv("EIGEN_REDIS_PASSWORD", null);
        sessionTtlSeconds    = Long.parseLong(lireEnv("EIGEN_SESSION_TTL_SECONDS", "86400"));
        etablissementCode    = lireEnv("EIGEN_ETABLISSEMENT_CODE", "LOCAL");

        log.info("EIGEN-Redis :: Host={}:{} | TTL={}s | Etablissement={}",
                redisHost, redisPort, sessionTtlSeconds, etablissementCode);

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(20);
            poolConfig.setMaxIdle(10);
            poolConfig.setMinIdle(2);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setMinEvictableIdleTime(Duration.ofSeconds(60));
            poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setMaxWait(Duration.ofSeconds(3));

            if (redisPassword != null && !redisPassword.isBlank()) {
                jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 3000, redisPassword);
            } else {
                jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 3000);
            }

            // Test de connexion
            try (var jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                log.info("EIGEN-Redis :: Connexion établie — PING:{} ✓", pong);
                redisDisponible = true;
            }

        } catch (Exception e) {
            log.error("EIGEN-Redis :: ATTENTION — Impossible de se connecter à Redis {}:{} : {}",
                    redisHost, redisPort, e.getMessage());
            log.error("EIGEN-Redis :: Les sessions NE SERONT PAS synchronisées tant que Redis est indisponible.");
            log.error("EIGEN-Redis :: IMPACT : le Gateway ne pourra pas valider les sessions — tous les accès seront refusés.");
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        log.info("EIGEN-Redis :: Session Manager opérationnel. Redis disponible: {}", redisDisponible);
    }

    @Override
    public void close() {
        log.info("EIGEN-Redis :: Fermeture du pool de connexions Redis...");
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            log.info("EIGEN-Redis :: Pool Redis fermé proprement ✓");
        }
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        if (!redisDisponible || jedisPool == null || jedisPool.isClosed()) {
            return new EigenSessionManagerNoOp(etablissementCode);
        }
        return new EigenSessionManager(session, jedisPool, sessionTtlSeconds, etablissementCode);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    private String lireEnv(String var, String defaut) {
        String val = System.getenv(var);
        return (val != null && !val.isBlank()) ? val.trim() : defaut;
    }
}
