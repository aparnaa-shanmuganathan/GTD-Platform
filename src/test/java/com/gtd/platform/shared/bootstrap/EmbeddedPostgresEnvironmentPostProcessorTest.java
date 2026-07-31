package com.gtd.platform.shared.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 (packaging): unit coverage for the per-OS app-data directory resolution used by the
 * bundled Postgres bootstrap. The process-management side (starting/stopping a real Postgres
 * server) is exercised end-to-end via a manual packaged-jar smoke test instead of an automated
 * test here, for the same reason the real GTD CSV import isn't exercised by every test class -
 * it is slow, OS/binary-dependent, and already proven working; this class covers the one pure,
 * fast-to-verify piece.
 */
class EmbeddedPostgresEnvironmentPostProcessorTest {

    @Test
    void resolvesWindowsAppDataDirectoryFromAppDataEnvVar() {
        Path result = EmbeddedPostgresEnvironmentPostProcessor.resolveAppDataRoot(
                "Windows 11", "C:\\Users\\admin", "C:\\Users\\admin\\AppData\\Roaming", null);

        assertThat(result).isEqualTo(Path.of("C:\\Users\\admin\\AppData\\Roaming", "GTD Platform", "data"));
    }

    @Test
    void fallsBackToUserHomeWhenWindowsAppDataEnvVarMissing() {
        Path result = EmbeddedPostgresEnvironmentPostProcessor.resolveAppDataRoot(
                "Windows 11", "C:\\Users\\admin", null, null);

        assertThat(result).isEqualTo(Path.of("C:\\Users\\admin", "GTD Platform", "data"));
    }

    @Test
    void resolvesMacAppDataDirectoryUnderApplicationSupport() {
        Path result = EmbeddedPostgresEnvironmentPostProcessor.resolveAppDataRoot(
                "Mac OS X", "/Users/admin", null, null);

        assertThat(result).isEqualTo(Path.of("/Users/admin", "Library", "Application Support", "GTD Platform"));
    }

    @Test
    void resolvesLinuxAppDataDirectoryFromXdgDataHomeWhenSet() {
        Path result = EmbeddedPostgresEnvironmentPostProcessor.resolveAppDataRoot(
                "Linux", "/home/admin", null, "/home/admin/.data");

        assertThat(result).isEqualTo(Path.of("/home/admin/.data", "gtd-platform"));
    }

    @Test
    void fallsBackToLocalShareOnLinuxWhenXdgDataHomeUnset() {
        Path result = EmbeddedPostgresEnvironmentPostProcessor.resolveAppDataRoot(
                "Linux", "/home/admin", null, null);

        assertThat(result).isEqualTo(Path.of("/home/admin", ".local", "share", "gtd-platform"));
    }
}
