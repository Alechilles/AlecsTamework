package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Prevents native mounted descent from issuing per-tick player velocity instructions.
 */
class NativeMountedDescentSafetyGuardTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final Path TAMEWORK_PATH = MAIN_JAVA.resolve(Paths.get(
            "com",
            "alechilles",
            "alecstamework",
            "Tamework.java"
    ));
    private static final Path DESCENT_SYSTEM_PATH = MAIN_JAVA.resolve(Paths.get(
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "systems",
            "NativeMountedDescentSystem.java"
    ));

    @Test
    void nativeMountDescentDoesNotDrivePlayerVelocityEachTick() throws IOException {
        String tamework = Files.readString(TAMEWORK_PATH, StandardCharsets.UTF_8);

        assertFalse(
                tamework.contains("NativeMountedDescentSystem"),
                "Native mounted descent must not register a per-tick player velocity system. "
                        + "Player velocity is client-authoritative and repeated instructions can accumulate."
        );
        assertFalse(
                Files.exists(DESCENT_SYSTEM_PATH),
                "Do not retain NativeMountedDescentSystem until the engine exposes a native mount-gravity hook."
        );
    }
}
