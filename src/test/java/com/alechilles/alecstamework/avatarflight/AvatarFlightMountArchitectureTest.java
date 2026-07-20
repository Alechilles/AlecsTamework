package com.alechilles.alecstamework.avatarflight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static safety checks for registration and command-buffer-only runtime mount cleanup. */
class AvatarFlightMountArchitectureTest {
    @Test
    void componentIdsAndSystemsAreRegistered() throws Exception {
        String registrar = read("src/main/java/com/alechilles/alecstamework/TameworkComponentRegistrar.java");
        String plugin = read("src/main/java/com/alechilles/alecstamework/Tamework.java");

        assertTrue(registrar.contains("TameworkAvatarFlightMountSession"));
        assertTrue(registrar.contains("TameworkAvatarFlightSource"));
        assertTrue(plugin.contains("new AvatarFlightMountSessionSystem("));
        assertTrue(plugin.contains("new AvatarFlightSourceRecoverySystem("));
        assertTrue(plugin.contains("new AvatarFlightSourceVisibilitySystem("));
        assertTrue(plugin.contains("new AvatarFlightDisconnectRecoveryService()::onPlayerDisconnect"));
        assertTrue(
                plugin.indexOf("new AvatarFlightMovementSystem(")
                        < plugin.indexOf("new AvatarFlightMountSessionSystem("),
                "System dependency targets must be registered before dependent systems"
        );
    }

    /** Protects clean disconnect cleanup and the missing-player source watchdog fallback. */
    @Test
    void disconnectEndsLiveSessionOnWorldThreadWithoutCapturingPlayerComponents() throws Exception {
        String recovery = read(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightDisconnectRecoveryService.java");

        assertTrue(recovery.contains("activator.preparePlayerDisconnect(playerUuid)"));
        assertTrue(recovery.contains("world.execute(() -> recoverOnWorldThread(world, playerUuid))"));
        assertTrue(recovery.contains("world.getEntityRef(playerUuid)"),
                "the world-thread callback must resolve a live ref from the stable UUID");
        assertTrue(recovery.contains("AvatarFlightMountLifecycleService.EndReason.DISCONNECT"));
        assertTrue(recovery.contains("finally"));
        assertTrue(recovery.contains("activator.finishPlayerDisconnect(playerUuid)"));
        assertFalse(recovery.contains("PlayerRef.getComponent(Player"));
    }

    /** Protects hard-restart recovery when both persisted mount halves still agree. */
    @Test
    void priorRuntimeSessionsCannotRemainPairedAfterRestart() throws Exception {
        String playerRecovery = read(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMountSessionSystem.java");
        String sourceRecovery = read(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSourceRecoverySystem.java");
        String session = read(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMountSessionComponent.java");
        String source = read(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSourceComponent.java");

        assertTrue(session.contains("new KeyedCodec<>(\"RuntimeEpoch\""));
        assertTrue(source.contains("new KeyedCodec<>(\"RuntimeEpoch\""));
        assertTrue(playerRecovery.contains("!AvatarFlightRuntimeEpoch.isCurrent(session.getRuntimeEpoch())"));
        assertTrue(playerRecovery.contains("!AvatarFlightRuntimeEpoch.isCurrent(source.getRuntimeEpoch())"));
        assertTrue(playerRecovery.contains("EndReason.SERVER_RESTART"));
        assertTrue(sourceRecovery.contains("AvatarFlightRuntimeEpoch.isCurrent(source.getRuntimeEpoch())"));
        assertTrue(sourceRecovery.contains("AvatarFlightRuntimeEpoch.isCurrent(session.getRuntimeEpoch())"));
    }

    /** Protects the regression where removing tracker output left the parked NPC visible. */
    @Test
    void parkedSourceNpcIsFilteredFromEntityViewersUntilRestore() throws Exception {
        String visibility = read(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSourceVisibilitySystem.java");
        String parking = read(
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightNpcParkingService.java");

        assertTrue(visibility.contains("EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP"));
        assertTrue(visibility.contains("Order.AFTER, EntityTrackerSystems.CollectVisible.class"));
        assertTrue(visibility.contains("commandBuffer.getComponent(targetRef, sourceType) != null"));
        assertTrue(visibility.contains("iterator.remove()"));
        assertFalse(parking.contains(
                "removeIfPresent(store, npcRef, EntityTrackerSystems.Visible.getComponentType())"));
    }

    /** Protects the regression where the normal F dismount packet skipped avatar-flight sessions. */
    @Test
    void dismountPacketEndsAvatarFlightBeforeOtherMountModes() throws Exception {
        String handler = read(
                "src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java");

        assertTrue(handler.contains("DismountNPC.PACKET_ID"));
        assertTrue(handler.contains("SyncInteractionChains.PACKET_ID"));
        assertTrue(handler.contains("update.initial && update.interactionType == InteractionType.Use"));
        assertTrue(handler.contains("handleInteractionChains((SyncInteractionChains) packet)"));
        assertTrue(handler.contains("handleAvatarFlightDismount(riderRef, store)"));
        assertTrue(handler.contains("AvatarFlightMountLifecycleService.EndReason.NORMAL"));
        assertTrue(handler.indexOf("handleAvatarFlightDismount(riderRef, store)")
                < handler.indexOf("TameworkRideRiderComponent rider ="));
    }

    @Test
    void runtimeSystemsDoNotWriteThroughStoreDirectly() throws Exception {
        for (String file : new String[]{
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMountSessionSystem.java",
                "src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightSourceRecoverySystem.java"
        }) {
            String source = read(file);
            assertFalse(source.contains("store.putComponent("), file);
            assertFalse(source.contains("store.removeComponent("), file);
            assertFalse(source.contains("store.tryRemoveComponent("), file);
            assertFalse(source.contains("PlayerRef.getComponent(Player"), file);
        }
    }

    @Test
    void promptActionAndExampleAssetsShareAvatarMountContract() throws Exception {
        String requirements = read(
                "src/main/java/com/alechilles/alecstamework/npc/actions/TameworkInteractRequirements.java");
        String effects = read(
                "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java");
        String fullTemplate = read(
                "src/main/resources/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json");
        String simpleTemplate = read(
                "src/main/resources/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Simple.json");

        assertTrue(requirements.contains("avatarFlightMountPreflight.canStart"));
        assertTrue(effects.contains("avatarFlightStarter.start"));
        for (String template : new String[]{fullTemplate, simpleTemplate}) {
            assertTrue(template.contains("TameworkAvatarFlight"));
            assertTrue(template.contains("\"AvatarFlightConfig\""));
        }
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
