package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards against reintroducing player-side movement effect resync.
 */
class PlayerMovementEffectDelegationGuardTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final Path TAMEWORK_PATH = MAIN_JAVA.resolve(Paths.get(
            "com",
            "alechilles",
            "alecstamework",
            "Tamework.java"
    ));
    private static final Path PLAYER_MOVEMENT_SYSTEM_PATH = MAIN_JAVA.resolve(Paths.get(
            "com",
            "alechilles",
            "alecstamework",
            "effects",
            "PlayerEffectMovementSystem.java"
    ));

    @Test
    void playerHorizontalSpeedEffectsRemainBaseGameOwned() throws IOException {
        String content = Files.readString(TAMEWORK_PATH, StandardCharsets.UTF_8);

        assertFalse(
                content.contains("PlayerEffectMovementSystem"),
                "Tamework must not register a player movement effect resync system. "
                        + "The base game already applies player HorizontalSpeedMultiplier effects."
        );
        assertFalse(
                Files.exists(PLAYER_MOVEMENT_SYSTEM_PATH),
                "Do not reintroduce PlayerEffectMovementSystem; it double-applies speed effects from other mods."
        );
    }
}
