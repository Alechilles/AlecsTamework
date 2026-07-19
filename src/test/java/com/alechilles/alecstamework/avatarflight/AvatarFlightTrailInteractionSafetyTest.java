package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the interaction-manager lifecycle contract used by avatar-flight trails. */
class AvatarFlightTrailInteractionSafetyTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightTrailService.java");

    @Test
    void trailChainsWaitForInteractionManagerToOwnAValidEntityRef() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("manager.queueExecuteChain(chain)"),
                "trail chains must execute during InteractionManager.tick, after its entity ref is assigned");
        assertFalse(source.contains("manager.executeChain("),
                "direct execution can crash the world when InteractionManager's internal ref is unset");
        assertTrue(source.contains("PENDING_CHAIN_ID"),
                "queued sustained trails must remain tracked before Hytale assigns a server chain id");
        assertTrue(source.contains("FORCE_REMOTE_SYNC = true"),
                "the owning client must execute trail chains so it also owns their cleanup lifecycle");
        assertFalse(source.contains("TRAIL_INTERACTION_TYPE, context, root, false"),
                "server-only trail chains can leave local client render state without matched cleanup");
    }
}
