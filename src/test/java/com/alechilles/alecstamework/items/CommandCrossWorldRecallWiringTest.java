package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the stable-player-ref boundary used by menu actions that span world transfers. */
class CommandCrossWorldRecallWiringTest {
    private static final Path ITEMS = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "items");

    @Test
    void menuRecallResolvesTheDestinationWorldPlayerAtClickTime() throws Exception {
        String menu = Files.readString(
                ITEMS.resolve("CommandMenuMoveService.java"), StandardCharsets.UTF_8);
        String resolver = Files.readString(
                ITEMS.resolve("WorldPlayerResolver.java"), StandardCharsets.UTF_8);

        assertTrue(menu.contains("WorldPlayerResolver.resolveCurrent(player)"));
        assertTrue(menu.contains("World world = livePlayer.world()"));
        assertTrue(menu.contains("Ref<EntityStore> playerRef = livePlayer.ref()"));
        assertTrue(resolver.contains("PlayerRef playerRef = capturedPlayer.getPlayerRef()"));
        assertTrue(resolver.contains("playerRef.getReference()"));
    }
}
