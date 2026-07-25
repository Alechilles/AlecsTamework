package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Rotation3fc;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedNpcProjectionSpawnerTest {
    private ComponentRegistry<EntityStore> registry;
    private Store<EntityStore> store;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistry<>();
        store = registry.addStore(null, null);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            registry.removeStore(store);
        }
        if (registry != null) {
            registry.shutdown();
        }
    }

    @Test
    void oneSpawnInstallsPlannedUuidLegacyUuidFullStateAndMarkerBeforeAdd() {
        RecordingGateway gateway = new RecordingGateway();
        PlannedNpcProjectionSpawner spawner = new PlannedNpcProjectionSpawner(
                new PlannedNpcProjectionSpawnPlanner(),
                gateway
        );
        UUID plannedNpcUuid = uuid(7);
        TameworkProjectionIdentityComponent marker = marker();

        PlannedNpcProjectionSpawner.SpawnResult result = spawner.spawn(
                request("tamed_test", plannedNpcUuid, marker)
        );

        assertEquals(PlannedNpcProjectionSpawner.Status.SPAWNED, result.status());
        assertTrue(result.isSuccess());
        assertEquals(1, gateway.spawnCalls);
        assertEquals(0, gateway.quarantineCalls);
        assertEquals(plannedNpcUuid, gateway.target.uuidComponentValue);
        assertEquals(plannedNpcUuid, gateway.target.legacyNpcUuid);
        assertEquals(
                java.util.List.of("uuid_component", "legacy_uuid", "restore_full_state"),
                gateway.target.steps
        );
        assertTrue(gateway.target.components.containsKey(
                CoopResidentStateRestorer.ComponentSlot.COMMAND_LINKS));
        TameworkProjectionIdentityComponent installedMarker = (TameworkProjectionIdentityComponent)
                gateway.target.components.get(CoopResidentStateRestorer.ComponentSlot.PROJECTION_IDENTITY);
        assertNotNull(installedMarker);
        assertNotSame(marker, installedMarker);
        assertEquals(marker.getOperationId(), installedMarker.getOperationId());
        assertEquals(-999.0, result.postAddWork().healthPercent());
        assertTrue(result.postAddWork().hasAttachmentWork());
    }

    @Test
    void invalidRequestDoesNotCallSpawnGateway() {
        RecordingGateway gateway = new RecordingGateway();
        PlannedNpcProjectionSpawner spawner = new PlannedNpcProjectionSpawner(
                new PlannedNpcProjectionSpawnPlanner(),
                gateway
        );

        PlannedNpcProjectionSpawner.SpawnResult result = spawner.spawn(
                request(" ", uuid(7), marker())
        );

        assertEquals(PlannedNpcProjectionSpawner.Status.INVALID_REQUEST, result.status());
        assertFalse(result.isSuccess());
        assertEquals(0, gateway.spawnCalls);
    }

    @Test
    void provisioningActivationMarkerIsAllowedForProjectionSpawn() {
        RecordingGateway gateway = new RecordingGateway();
        PlannedNpcProjectionSpawner spawner = new PlannedNpcProjectionSpawner(
                new PlannedNpcProjectionSpawnPlanner(),
                gateway
        );
        TameworkProjectionIdentityComponent marker =
                new TameworkProjectionIdentityComponent(
                        "profile-a",
                        "operation-a",
                        TameworkProjectionIdentityComponent
                                .KIND_PROVISIONING_ACTIVATION,
                        "receipt-a",
                        null,
                        0L
                );

        PlannedNpcProjectionSpawner.SpawnResult result = spawner.spawn(
                request("tamed_test", uuid(7), marker)
        );

        assertEquals(
                PlannedNpcProjectionSpawner.Status.SPAWNED,
                result.status()
        );
        assertEquals(1, gateway.spawnCalls);
    }

    @Test
    void spawnFailureReturnsNoHandlesOrPostAddWork() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.resultStatus = PlannedNpcProjectionSpawner.Status.SPAWN_FAILED;
        PlannedNpcProjectionSpawner spawner = new PlannedNpcProjectionSpawner(
                new PlannedNpcProjectionSpawnPlanner(),
                gateway
        );

        PlannedNpcProjectionSpawner.SpawnResult result = spawner.spawn(
                request("tamed_test", uuid(7), marker())
        );

        assertEquals(PlannedNpcProjectionSpawner.Status.SPAWN_FAILED, result.status());
        assertFalse(result.isSuccess());
        assertEquals(1, gateway.spawnCalls);
        assertEquals(null, result.reference());
        assertEquals(null, result.npc());
        assertEquals(null, result.postAddWork());
    }

    @Test
    void identityMismatchFailsClosedAndRequestsQuarantine() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.returnMismatchedUuid = true;
        PlannedNpcProjectionSpawner spawner = new PlannedNpcProjectionSpawner(
                new PlannedNpcProjectionSpawnPlanner(),
                gateway
        );

        PlannedNpcProjectionSpawner.SpawnResult result = spawner.spawn(
                request("tamed_test", uuid(7), marker())
        );

        assertEquals(PlannedNpcProjectionSpawner.Status.IDENTITY_MISMATCH, result.status());
        assertFalse(result.isSuccess());
        assertEquals(1, gateway.spawnCalls);
        assertEquals(1, gateway.quarantineCalls);
        assertEquals(null, result.postAddWork());
    }

    @Test
    void productionGatewayUsesNpcPluginSevenArgumentPreAddOverload() throws Exception {
        Method overload = NPCPlugin.class.getMethod(
                "spawnEntity",
                Store.class,
                int.class,
                Vector3dc.class,
                Rotation3fc.class,
                Model.class,
                TriConsumer.class,
                TriConsumer.class
        );
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "HytalePlannedNpcProjectionSpawnGateway.java"
        ));

        assertNotNull(overload);
        assertTrue(source.contains("npcPlugin.spawnEntity("));
        assertTrue(source.contains("(npc, holder, callbackStore) ->"));
        assertTrue(source.contains("holder.putComponent(type, new UUIDComponent(plannedNpcUuid))"));
        assertTrue(source.contains("npc.setLegacyUUID(plannedNpcUuid)"));
        assertTrue(source.contains("restorer.restoreToHolder(holder, snapshot, projectionMarker)"));
    }

    private PlannedNpcProjectionSpawner.SpawnRequest request(
            String roleId,
            UUID plannedNpcUuid,
            TameworkProjectionIdentityComponent marker) {
        return new PlannedNpcProjectionSpawner.SpawnRequest(
                roleId,
                plannedNpcUuid,
                fullSnapshot(),
                marker,
                new Vector3d(1.0, 2.0, 3.0),
                new Rotation3f(),
                store
        );
    }

    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot() {
        return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                uuid(1),
                null,
                -1,
                "tamed_test",
                new TameworkCommandLinksComponent(uuid(2), new String[] {"tool-a"}),
                null,
                null,
                null,
                new TameworkHappinessComponent("happy", 0.8, -101L),
                null,
                null,
                null,
                null,
                null,
                null,
                new TameworkAttachmentsComponent("attachments", Map.of("head", "crest")),
                -999.0,
                -1_000L
        );
    }

    private TameworkProjectionIdentityComponent marker() {
        return new TameworkProjectionIdentityComponent(
                "profile-a",
                "operation-a",
                TameworkProjectionIdentityComponent.KIND_RECOVERY,
                null,
                uuid(1),
                4L
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static final class RecordingGateway implements PlannedNpcProjectionSpawner.SpawnGateway {
        private int spawnCalls;
        private int quarantineCalls;
        private boolean returnMismatchedUuid;
        private PlannedNpcProjectionSpawner.Status resultStatus = PlannedNpcProjectionSpawner.Status.SPAWNED;
        private RecordingTarget target;

        @Override
        public PlannedNpcProjectionSpawner.GatewayResult spawn(
                PlannedNpcProjectionSpawner.SpawnRequest request,
                PlannedNpcProjectionSpawner.PreAddInstaller installer) {
            spawnCalls++;
            if (resultStatus != PlannedNpcProjectionSpawner.Status.SPAWNED) {
                return PlannedNpcProjectionSpawner.GatewayResult.failed(resultStatus);
            }
            target = new RecordingTarget();
            CoopResidentStateRestorer.PostAddWork work = installer.install(target);
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(target.legacyNpcUuid);
            UUID observedComponentUuid = returnMismatchedUuid ? uuid(88) : target.uuidComponentValue;
            return new PlannedNpcProjectionSpawner.GatewayResult(
                    PlannedNpcProjectionSpawner.Status.SPAWNED,
                    new PlannedNpcProjectionSpawner.SpawnedProjection(
                            new Ref<>(null, 7),
                            npc,
                            observedComponentUuid,
                            target.legacyNpcUuid,
                            (TameworkProjectionIdentityComponent) target.components.get(
                                    CoopResidentStateRestorer.ComponentSlot.PROJECTION_IDENTITY),
                            work
                    )
            );
        }

        @Override
        public void quarantine(PlannedNpcProjectionSpawner.SpawnedProjection spawned) {
            quarantineCalls++;
            spawned.npc().setToDespawn();
        }
    }

    private static final class RecordingTarget implements PlannedNpcProjectionSpawner.PreAddTarget {
        private final ArrayList<String> steps = new ArrayList<>();
        private final EnumMap<CoopResidentStateRestorer.ComponentSlot, Component<EntityStore>> components =
                new EnumMap<>(CoopResidentStateRestorer.ComponentSlot.class);
        private UUID uuidComponentValue;
        private UUID legacyNpcUuid;

        @Override
        public void replaceUuidComponent(UUID plannedNpcUuid) {
            steps.add("uuid_component");
            uuidComponentValue = plannedNpcUuid;
        }

        @Override
        public void setLegacyNpcUuid(UUID plannedNpcUuid) {
            steps.add("legacy_uuid");
            legacyNpcUuid = plannedNpcUuid;
        }

        @Override
        public CoopResidentStateRestorer.PostAddWork restoreFullState(
                CoopResidentStateRestorer restorer,
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                TameworkProjectionIdentityComponent projectionMarker) {
            steps.add("restore_full_state");
            return restorer.restore(components::put, snapshot, projectionMarker);
        }
    }
}
