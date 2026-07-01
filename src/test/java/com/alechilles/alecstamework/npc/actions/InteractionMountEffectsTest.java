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
        String mountSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        String cleanupSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/MountedGlideStaleStateCleanup.java"
        ));

        assertTrue(mountSource.contains("TameworkMount debug: stage=%s"));
        assertTrue(mountSource.contains("missing_required_components"));
        assertTrue(mountSource.contains("existing_mount_state"));
        assertTrue(mountSource.contains("native_attach"));
        assertTrue(cleanupSource.contains("stale_rider_cleanup"));
        assertTrue(mountSource.contains("MountedGlideStaleStateCleanup.clearInvalidRiderState"));
        assertTrue(mountSource.indexOf("MountedGlideStaleStateCleanup.clearInvalidRiderState")
                < mountSource.indexOf("\"existing_mount_state\""));
    }

    private static Player newPlayerWithoutServerInit() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (Player) unsafe.allocateInstance(Player.class);
    }
}
