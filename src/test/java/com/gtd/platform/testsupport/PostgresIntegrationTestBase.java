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
 */
@ActiveProfiles("test")
public abstract class PostgresIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(PostgresIntegrationTestBase.class);
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer<?> container;
    private static EmbeddedPostgres embeddedPostgres;

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable()) {
            log.info("Docker detected - using Testcontainers PostgreSQL ({})", POSTGRES_IMAGE);
            container = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
            registry.add("spring.datasource.driver-class-name", container::getDriverClassName);
        } else {
            log.info("Docker not available - falling back to io.zonky.test embedded-postgres");
            try {
                embeddedPostgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to start embedded Postgres for tests", e);
            }
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
