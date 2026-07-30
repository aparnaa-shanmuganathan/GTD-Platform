package com.gtd.platform.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Base class for integration tests that need a real PostgreSQL 16 database.
 *
 * <p>Implements the test database strategy frozen in CLAUDE.md section 2/12:
 * prefer a Testcontainers-managed PostgreSQL 16 container when Docker is
 * available on the dev/CI machine, and fall back to a Docker-free
 * {@code io.zonky.test:embedded-postgres} instance (a real Postgres binary,
 * not H2) when it is not. Either way, tests run against genuine Postgres
 * semantics (materialized views, {@code CONCURRENTLY}, advisory locks,
 * JSONB, partial unique indexes) that this schema depends on.
 *
 * <p>Subclasses just need {@code @SpringBootTest} and
 * {@code @ActiveProfiles("test")}; datasource properties are registered here
 * via {@link DynamicPropertySource} before the Spring context starts.
 *
 * <p><b>Exactly one container/embedded instance for the entire test JVM,
 * ever - never one per distinct Spring context configuration.</b> {@code
 * container}/{@code embeddedPostgres} are {@code static}, so they are shared
 * by every subclass regardless of which Spring context (e.g. plain {@code
 * @SpringBootTest} vs {@code webEnvironment = RANDOM_PORT}) it boots -
 * {@link DynamicPropertySource} methods re-run fresh for each distinct
 * context configuration, though, so without the {@code if (container ==
 * null ...)} guard below, a second (differently-configured) test class
 * starting up would silently start a brand-new instance and overwrite these
 * static fields out from under any other context still alive and using the
 * old one. That is exactly what caused {@code SessionPersistenceAcrossRestartTest}
 * to intermittently fail once enough test classes existed for Surefire's
 * natural ordering to interleave a non-{@code RANDOM_PORT} test between two
 * {@code RANDOM_PORT} ones: that test reads {@code spring.datasource.url}
 * live off the (cached, reused) {@code RANDOM_PORT} context's {@code
 * Environment} to boot a second, independent app instance against "the same
 * database" - but {@code DynamicPropertySource}-registered properties are
 * lazily re-evaluated {@code Supplier}s, not values snapshotted once, so
 * that lookup silently returned whichever instance the static field pointed
 * to *at that moment* (possibly a different, unrelated one started meanwhile
 * by the interleaved test), rather than the one the RANDOM_PORT context's
 * own already-running {@code HikariDataSource} was actually connected to.
 * See CLAUDE.md section 12, Phase 6 decisions, for the full incident.
 */
@ActiveProfiles("test")
public abstract class PostgresIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(PostgresIntegrationTestBase.class);
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer<?> container;
    private static EmbeddedPostgres embeddedPostgres;

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        if (container == null && embeddedPostgres == null) {
            if (isDockerAvailable()) {
                log.info("Docker detected - using Testcontainers PostgreSQL ({})", POSTGRES_IMAGE);
                container = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
                container.start();
            } else {
                log.info("Docker not available - falling back to io.zonky.test embedded-postgres");
                try {
                    embeddedPostgres = EmbeddedPostgres.builder().start();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to start embedded Postgres for tests", e);
                }
            }
        }

        if (container != null) {
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
            registry.add("spring.datasource.driver-class-name", container::getDriverClassName);
        } else {
            registry.add("spring.datasource.url",
                    () -> embeddedPostgres.getJdbcUrl("postgres", "postgres"));
            registry.add("spring.datasource.username", () -> "postgres");
            registry.add("spring.datasource.password", () -> "postgres");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
    }

    private static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
