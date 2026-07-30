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
    void nativeMountDelegatesScaledRiderSettingsBeforeEmptyRoleChange() throws Exception {
        String mountSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        String applicationSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/NativeMountMovementApplication.java"
        ));
        int nativeStart = mountSource.indexOf("private boolean applyNativeMount");
        int nativeEnd = mountSource.indexOf("private void logUnknownMountMode");
        String nativeMountSource = mountSource.substring(nativeStart, nativeEnd);

        assertTrue(mountSource.contains("NativeMountMovementApplication"));
        assertTrue(applicationSource.contains("CompanionMovementSpeedResolver"));
        assertTrue(applicationSource.contains("CompanionProgressionModifierService.resolveMultiplier"));
        assertTrue(applicationSource.indexOf("applyScaledSettings")
                < applicationSource.indexOf("RoleChangeSystem.requestRoleChange"));
        assertFalse(nativeMountSource.contains("applyMovementConfig("));
    }

    @Test
    void nativeMountMovementWiringIsExtractedFromInteractionMountEffects() throws Exception {
        String mountSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        String applicationSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/NativeMountMovementApplication.java"
        ));

        assertTrue(mountSource.contains("nativeMountMovementApplication.apply(request)"));
        assertTrue(applicationSource.contains("CompanionMovementSpeedResolver"));
        assertTrue(applicationSource.contains("applyScaledSettings"));
        assertTrue(applicationSource.indexOf("applyScaledSettings")
                < applicationSource.indexOf("RoleChangeSystem.requestRoleChange"));
        assertTrue(Files.readAllLines(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        )).size() <= 800);
    }

    @Test
    void nativeMountApplicationKeepsReasonSpecificFailureDiagnostics() throws Exception {
        String applicationSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/NativeMountMovementApplication.java"
        ));

        assertTrue(applicationSource.contains("npc_mount_component_type_unavailable"));
        assertTrue(applicationSource.contains("npc_already_has_mount_component"));
        assertTrue(applicationSource.contains("missing_npc_component"));
        assertTrue(applicationSource.contains("missing_player_component"));
        assertTrue(applicationSource.contains("missing_player_ref_component"));
        assertTrue(applicationSource.contains("missing_role_index"));
        assertTrue(applicationSource.contains("ensure_npc_mount_failed"));
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
        assertTrue(mountSource.contains("native_npc_mount_attach"));
        assertTrue(cleanupSource.contains("stale_rider_cleanup"));
        assertFalse(cleanupSource.contains("MountedComponent"));
        assertTrue(mountSource.contains("MountedGlideStaleStateCleanup.clearInvalidRiderState"));
        assertTrue(mountSource.indexOf("MountedGlideStaleStateCleanup.clearInvalidRiderState")
                < mountSource.indexOf("\"existing_mount_state\""));
    }

    @Test
    void mountedGlideDefaultsToNativeMountMovementConfig() throws Exception {
        String mountSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        int glideStart = mountSource.indexOf("private boolean applyTameworkMountedGlideMount");
        int glideEnd = mountSource.indexOf("private String resolveGlideMovementConfigId");
        String glideMountSource = mountSource.substring(glideStart, glideEnd);

        assertTrue(glideMountSource.contains("resolveGlideMovementConfigId(role)"));
        assertTrue(mountSource.contains("MOUNT_GLIDE_MOVEMENT_CONFIG_PARAM = \"MountGlideMovementConfig\""));
        assertTrue(mountSource.contains(
                "DEFAULT_MOUNT_GLIDE_MOVEMENT_CONFIG_ID = DEFAULT_MOUNT_MOVEMENT_CONFIG_ID"
        ));
        assertFalse(glideMountSource.contains("DEFAULT_MOUNT_MOVEMENT_CONFIG_PARAM"));
        assertTrue(Files.exists(Path.of(
                "src/main/resources/Server/Entity/MovementConfig/Tamework_Mounted_Glide_Rider.json"
        )));
    }

    @Test
    void mountedGlideRequestsNativeRoleChangeAfterClearingStatusAnimation() throws Exception {
        String mountSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        int glideStart = mountSource.indexOf("private boolean applyTameworkMountedGlideMount");
        int glideEnd = mountSource.indexOf("private String resolveGlideMovementConfigId");
        String glideMountSource = mountSource.substring(glideStart, glideEnd);

        assertTrue(glideMountSource.indexOf("clearStatusAnimation(npcRef, npcComponent, store)")
                < glideMountSource.indexOf("RoleChangeSystem.requestRoleChange"));
        assertFalse(glideMountSource.contains("applyRideState(npcRef, role, store, glideState)"));
        assertFalse(glideMountSource.contains(
                "role.setActiveMotionController(npcRef, npcComponent, glideController, store)"
        ));
    }

    @Test
    void mountedGlideLeavesInteractableForNativeMountRoleChange() throws Exception {
        String mountSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        int glideStart = mountSource.indexOf("private boolean applyTameworkMountedGlideMount");
        int glideEnd = mountSource.indexOf("private String resolveGlideMovementConfigId");
        String glideMountSource = mountSource.substring(glideStart, glideEnd);

        assertFalse(glideMountSource.contains("tryRemoveComponent(npcRef, Interactable.getComponentType())"));
    }

    @Test
    void mountedGlideMountDoesNotRequireLegacyMountedComponent() throws Exception {
        String mountSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
        ));
        int glideStart = mountSource.indexOf("private boolean applyTameworkMountedGlideMount");
        int glideEnd = mountSource.indexOf("private String resolveGlideMovementConfigId");
        String glideMountSource = mountSource.substring(glideStart, glideEnd);

        assertFalse(glideMountSource.contains("MountedComponent.getComponentType()"));
        assertFalse(glideMountSource.contains("mountedType"));
        assertTrue(glideMountSource.contains("nativeMountType"));
        assertTrue(glideMountSource.contains("MountedGlideStaleStateCleanup.clearInvalidRiderState"));
    }

    @Test
    void mountedGlideCleanupRequestsOriginalNativeRoleForRemounting() throws Exception {
        String cleanupSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java"
        ));
        String packetSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java"
        ));
        String staleCleanupSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/MountedGlideStaleStateCleanup.java"
        ));

        assertTrue(cleanupSource.contains("RoleChangeSystem.requestRoleChange"));
        assertTrue(cleanupSource.contains("nativeMount.getOriginalRoleIndex()"));
        assertTrue(packetSource.contains("RoleChangeSystem.requestRoleChange"));
        assertTrue(packetSource.contains("nativeMount.getOriginalRoleIndex()"));
        assertTrue(staleCleanupSource.contains("RoleChangeSystem.requestRoleChange"));
        assertTrue(staleCleanupSource.contains("nativeMount.getOriginalRoleIndex()"));
    }

    private static Player newPlayerWithoutServerInit() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (Player) unsafe.allocateInstance(Player.class);
    }
}
