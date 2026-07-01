package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.core.entity.entities.Player;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests native mount state guards used by optimized mount interactions. */
class InteractionMountEffectsTest {

    @Test
    void activeNativeMountRequiresPositiveNetworkId() throws Exception {
        Player player = newPlayerWithoutServerInit();

        player.setMountEntityId(-1);
        assertFalse(InteractionMountEffects.hasActiveNativeMount(player));

        player.setMountEntityId(0);
        assertFalse(InteractionMountEffects.hasActiveNativeMount(player));

        player.setMountEntityId(42);
        assertTrue(InteractionMountEffects.hasActiveNativeMount(player));
    }

    @Test
    void mountedGlideMountReportsActionableDebugStages() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));

        assertTrue(source.contains("TameworkMount debug: stage=%s"));
        assertTrue(source.contains("missing_required_components"));
        assertTrue(source.contains("existing_mount_state"));
        assertTrue(source.contains("native_attach"));
    }

    private static Player newPlayerWithoutServerInit() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (Player) unsafe.allocateInstance(Player.class);
    }
}
