package com.gtd.platform.shared.bootstrap;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 11 (offline packaging): boots a real, bundled PostgreSQL 16 server for the jpackage'd
 * desktop installer, so an end user installs and runs this platform with nothing pre-installed
 * except the OS itself (CLAUDE.md section 3), never a manual "go install Postgres yourself" step.
 *
 * <p>Reuses the same io.zonky.test:embedded-postgres library and real Postgres 16 binaries this
 * project already depends on for its Testcontainers-unavailable test fallback
 * (PostgresIntegrationTestBase) - not a second, separately-vetted embedding mechanism. The
 * library's design is a general-purpose "manage a real Postgres process from Java" API; tests
 * are simply its most common use case, not an inherent restriction.
 *
 * <p>Activation is deliberately narrow and explicit, never inferred: this only starts a bundled
 * server when BOTH the {@code gtd.packaged=true} system property (set solely by the jpackage
 * app image's launcher configuration, never by a developer's normal {@code mvn spring-boot:run})
 * AND the {@code packaged} Spring profile are present. If an admin has already pointed
 * {@code spring.datasource.url} at a real external Postgres 16 instance (env var, system
 * property, or command-line argument - anything already resolvable in the environment by the
 * time this runs, i.e. before this class's own property source is added), that external
 * configuration wins and no bundled server is started - this is an escape hatch, not the only
 * supported path.
 */
public final class EmbeddedPostgresEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresEnvironmentPostProcessor.class);

    private static final String PACKAGED_SYSTEM_PROPERTY = "gtd.packaged";
    private static final String PACKAGED_PROFILE = "packaged";
    private static final String APP_DIR_NAME_DISPLAY = "GTD Platform";
    private static final String APP_DIR_NAME_OTHER = "gtd-platform";
    private static final String DATABASE_NAME = "gtd_platform";
    private static final String SUPERUSER = "postgres";

    private static volatile EmbeddedPostgres runningInstance;

    @Override
    public int getOrder() {
        // After Spring Boot's own config-data loading (so application-packaged.yml has already
        // been applied, and active profiles are already known) but well before anything reads
        // spring.datasource.* for real (DataSource autoconfiguration runs during context
        // refresh, far later than any EnvironmentPostProcessor).
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Boolean.getBoolean(PACKAGED_SYSTEM_PROPERTY)) {
            return;
        }
        if (!environment.acceptsProfiles(Profiles.of(PACKAGED_PROFILE))) {
            log.warn("gtd.packaged=true was set but the 'packaged' Spring profile is not active - " +
                    "bundled Postgres will not be started. Activate it via SPRING_PROFILES_ACTIVE=packaged.");
            return;
        }
        if (environment.containsProperty("spring.datasource.url")) {
            log.info("spring.datasource.url is already configured externally - skipping the bundled " +
                    "Postgres server and connecting to the configured external database instead.");
            return;
        }

        try {
            EmbeddedPostgres pg = startBundledPostgres();
            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", "jdbc:postgresql://localhost:" + pg.getPort() + "/" + DATABASE_NAME);
            props.put("spring.datasource.username", SUPERUSER);
            props.put("spring.datasource.password", SUPERUSER);
            props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
            environment.getPropertySources().addFirst(new MapPropertySource("gtdBundledPostgres", props));
        } catch (IOException e) {
            // Deliberately fatal, not swallowed: a packaged build missing its platform's
            // embedded-postgres-binaries artifact (wrong OS/arch installer) or a corrupted data
            // directory means the platform cannot function, and failing loudly at startup beats
            // a confusing later "connection refused" from Flyway/JPA.
            throw new IllegalStateException("Failed to start the bundled PostgreSQL 16 server", e);
        }
    }

    private static synchronized EmbeddedPostgres startBundledPostgres() throws IOException {
        if (runningInstance != null) {
            return runningInstance;
        }
        Path appDataRoot = resolveAppDataRoot();
        Path dataDirectory = appDataRoot.resolve("pgdata");
        Path binariesCacheDirectory = appDataRoot.resolve("pg-runtime");
        Files.createDirectories(appDataRoot);

        log.info("Starting bundled PostgreSQL 16 server (data directory: {})", dataDirectory);
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setDataDirectory(dataDirectory)
                .setCleanDataDirectory(false)
                .setOverrideWorkingDirectory(binariesCacheDirectory.toFile())
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (IOException e) {
                log.warn("Error stopping the bundled PostgreSQL server on shutdown", e);
            }
        }, "gtd-embedded-postgres-shutdown"));

        ensureDatabaseExists(pg);
        runningInstance = pg;
        return pg;
    }

    private static void ensureDatabaseExists(EmbeddedPostgres pg) throws IOException {
        DataSource postgresDb = pg.getPostgresDatabase();
        try (Connection connection = postgresDb.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + DATABASE_NAME + "'")) {
                if (!rs.next()) {
                    statement.executeUpdate("CREATE DATABASE " + DATABASE_NAME);
                    log.info("Created database '{}' in the bundled PostgreSQL server", DATABASE_NAME);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to ensure the '" + DATABASE_NAME + "' database exists", e);
        }
    }

    private static Path resolveAppDataRoot() {
        return resolveAppDataRoot(
                System.getProperty("os.name", ""),
                System.getProperty("user.home"),
                System.getenv("APPDATA"),
                System.getenv("XDG_DATA_HOME"));
    }

    /**
     * Pure function (no System.* calls) so the per-OS branching can be unit-tested directly,
     * the same style established elsewhere in this project for standalone-computable logic
     * (e.g. service.agg.GrowthRateCalculator).
     */
    static Path resolveAppDataRoot(String osName, String userHome, String windowsAppDataEnv, String xdgDataHomeEnv) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Path.of(windowsAppDataEnv != null ? windowsAppDataEnv : userHome, APP_DIR_NAME_DISPLAY, "data");
        } else if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", APP_DIR_NAME_DISPLAY);
        } else {
            Path base = xdgDataHomeEnv != null ? Path.of(xdgDataHomeEnv) : Path.of(userHome, ".local", "share");
            return base.resolve(APP_DIR_NAME_OTHER);
        }
    }
}
