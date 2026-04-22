package ga.eigen.keycloak.listener;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EIGEN IAM — Kafka Event Listener en mode dégradé (No-Op)
 *
 * <p>Utilisé quand Kafka n'est pas disponible au démarrage de Keycloak.
 * Loggue les événements qui auraient dû être publiés sans planter Keycloak.
 *
 * <p>Ce comportement de résilience est intentionnel : Keycloak doit pouvoir
 * fonctionner même si Kafka est temporairement indisponible. Les événements
 * perdus pendant cette période seront récupérés via les mécanismes de
 * reconciliation du Référentiel Python.
 *
 * @author EIGEN IAM Team
 * @version 1.0.0
 */
public class EigenKafkaEventListenerNoOp implements EventListenerProvider {

    private static final Logger log = LoggerFactory.getLogger(EigenKafkaEventListenerNoOp.class);

    private final String topicPrefix;
    private final String etablissementCode;

    public EigenKafkaEventListenerNoOp(String topicPrefix, String etablissementCode) {
        this.topicPrefix = topicPrefix;
        this.etablissementCode = etablissementCode;
    }

    @Override
    public void onEvent(Event event) {
        log.warn("EIGEN-Kafka [NO-OP] :: Événement {} non publié (Kafka indisponible) | userId={} | realm={}",
                event.getType(), event.getUserId(), event.getRealmId());
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        log.warn("EIGEN-Kafka [NO-OP] :: Événement admin {} {} non publié (Kafka indisponible) | realm={}",
                adminEvent.getResourceType(), adminEvent.getOperationType(), adminEvent.getRealmId());
    }

    @Override
    public void close() {
        // Rien à fermer
    }
}
