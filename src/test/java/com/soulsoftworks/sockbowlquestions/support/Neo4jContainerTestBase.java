package com.soulsoftworks.sockbowlquestions.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one Neo4j Testcontainers base for sockbowl-questions tests (M2 Q2; reused by
 * Q3's {@code KeycloakAuthITBase} and M3's integration tests, which must extend it
 * rather than start another Neo4j).
 *
 * <p>A single static container is started once per JVM and shared by every subclass,
 * so Spring's cached application contexts never point at a stopped container. Ryuk
 * removes it when the JVM exits. {@link ServiceConnection} wires
 * {@code spring.neo4j.*} to it; subclasses pick their own Spring test annotation
 * (usually {@code @SpringBootTest}).
 *
 * <p>The image tag matches the Neo4j version in {@code sockbowl-docker/docker-compose.yml}.
 * Tests must clean up the nodes they create (or use unique names/ids), because the
 * database is shared across test classes.
 */
@Testcontainers
public abstract class Neo4jContainerTestBase {

    /** Keep in step with the neo4j image in sockbowl-docker/docker-compose.yml. */
    public static final String NEO4J_IMAGE = "neo4j:2026.09";

    @ServiceConnection
    protected static final Neo4jContainer NEO4J = new Neo4jContainer(DockerImageName.parse(NEO4J_IMAGE))
            .withoutAuthentication()
            // BankRepository/BankStatsRepository use apoc.map.fromPairs (matches the
            // apoc-2026.09.0-core.jar sockbowl-docker's plugin script installs for this
            // same image tag); without it, any test that hits those queries for real
            // (not through a @MockitoBean service layer) gets "Unknown function".
            .withPlugins("apoc")
            .withEnv("NEO4J_dbms_security_procedures_unrestricted", "apoc.*")
            .withEnv("NEO4J_dbms_security_procedures_allowlist", "apoc.*");

    static {
        NEO4J.start();
    }
}
