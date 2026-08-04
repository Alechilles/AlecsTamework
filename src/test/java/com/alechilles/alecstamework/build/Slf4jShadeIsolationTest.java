package com.alechilles.alecstamework.build;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the private SLF4J facade and binding used by the shaded SQLite runtime. */
class Slf4jShadeIsolationTest {
    private static final Path BUILD = Path.of("build.gradle");
    private static final Path PROPERTIES = Path.of("gradle.properties");
    private static final String PRIVATE_SLF4J =
            "com.alechilles.alecstamework.shadow.slf4j";

    @Test
    void apiAndNopBindingUseTheSamePinnedSlf4jVersion() throws Exception {
        assertTrue(Files.readString(PROPERTIES).contains("slf4j_version=1.7.36"));

        String build = Files.readString(BUILD);
        assertTrue(build.contains("org.slf4j:slf4j-api:${property('slf4j_version')}"));
        assertTrue(build.contains("org.slf4j:slf4j-nop:${property('slf4j_version')}"));
    }

    @Test
    void shadePackagesApiAndNopBindingInsideThePrivateNamespace() throws Exception {
        String build = Files.readString(BUILD);
        assertTrue(build.contains("relocate 'org.slf4j', '" + PRIVATE_SLF4J + "'"));
    }

    @Test
    void sourceDoesNotPublishAnUnrelocatedSlf4jServiceProvider() throws Exception {
        String build = Files.readString(BUILD);

        assertFalse(
                build.contains("META-INF/services/org.slf4j"),
                "SLF4J 1.7 NOP uses the relocated StaticLoggerBinder, not a global service provider."
        );
        assertFalse(
                build.contains("org.slf4j:slf4j-simple"),
                "Tamework must not expose a logging provider that can collide with other mods."
        );
    }
}
