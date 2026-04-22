# =============================================================================
# EIGEN IAM — Dockerfile :: Keycloak Local (par Établissement)
# =============================================================================

FROM maven:3.9.6-eclipse-temurin-17 AS spi-builder

WORKDIR /build

# Build eigen-kafka-listener
COPY keycloak/extensions/eigen-kafka-listener/pom.xml kafka-listener-pom.xml
RUN mvn dependency:go-offline -f kafka-listener-pom.xml -q
COPY keycloak/extensions/eigen-kafka-listener/src ./kafka-listener-src
RUN mvn package -f kafka-listener-pom.xml -DskipTests -q -pl . \
    && mv target/eigen-kafka-listener-*.jar /build/

# Build eigen-session-manager
COPY keycloak/extensions/eigen-session-manager/pom.xml session-manager-pom.xml
RUN mvn dependency:go-offline -f session-manager-pom.xml -q
COPY keycloak/extensions/eigen-session-manager/src ./session-manager-src
RUN mvn package -f session-manager-pom.xml -DskipTests -q -pl . \
    && mv target/eigen-session-manager-*.jar /build/

RUN ls -la /build/*.jar

# =============================================================================
FROM quay.io/keycloak/keycloak:24.0.5 AS keycloak-local-eigen

LABEL maintainer="EIGEN IAM Team <iam@eigen.ga>"
LABEL org.opencontainers.image.title="EIGEN Keycloak Local"
LABEL org.opencontainers.image.description="Keycloak Local EIGEN — IAM par Établissement"
LABEL org.opencontainers.image.version="1.0.0"
LABEL eigen.component="keycloak-local"

COPY --from=spi-builder /build/eigen-kafka-listener-*.jar /opt/keycloak/providers/
COPY --from=spi-builder /build/eigen-session-manager-*.jar /opt/keycloak/providers/

COPY keycloak/themes/eigen-local /opt/keycloak/themes/eigen-local
COPY keycloak/realm-config /opt/keycloak/data/import

RUN /opt/keycloak/bin/kc.sh build \
    --db=postgres \
    --features=token-exchange,admin-fine-grained-authz,declarative-user-profile \
    --health-enabled=true \
    --metrics-enabled=true

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
