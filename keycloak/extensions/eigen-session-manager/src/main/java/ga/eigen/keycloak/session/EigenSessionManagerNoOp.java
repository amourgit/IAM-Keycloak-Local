package ga.eigen.keycloak.session;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EIGEN IAM — Session Manager en mode dégradé (Redis indisponible)
 *
 * <p><strong>ATTENTION</strong> : quand ce listener est actif, le Gateway Master
 * ne peut PAS valider les sessions (Redis vide). Toutes les requêtes qui passent
 * par la vérification de session seront refusées.
 *
 * <p>C'est le comportement correct par défaut : fail-secure.
 * En cas d'indisponibilité Redis, le système est inaccessible plutôt qu'ouvert.
 *
 * @author EIGEN IAM Team
 * @version 1.0.0
 */
public class EigenSessionManagerNoOp implements EventListenerProvider {

    private static final Logger log = LoggerFactory.getLogger(EigenSessionManagerNoOp.class);
    private final String etablissementCode;

    public EigenSessionManagerNoOp(String etablissementCode) {
        this.etablissementCode = etablissementCode;
        log.error("EIGEN-Redis [NO-OP] :: Session Manager en mode dégradé — Redis INDISPONIBLE");
        log.error("EIGEN-Redis [NO-OP] :: IMPACT CRITIQUE : le Gateway refusera toutes les requêtes (session_not_found)");
        log.error("EIGEN-Redis [NO-OP] :: Action requise : vérifier la disponibilité de Redis et redémarrer Keycloak");
    }

    @Override
    public void onEvent(Event event) {
        log.warn("EIGEN-Redis [NO-OP] :: Événement {} ignoré — Redis indisponible | userId={} | etablissement={}",
                event.getType(), event.getUserId(), etablissementCode);
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        log.warn("EIGEN-Redis [NO-OP] :: Événement admin ignoré — Redis indisponible | etablissement={}",
                etablissementCode);
    }

    @Override
    public void close() {}
}
