package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.damage.RecentSpawnProtectionService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.AttemptGateway;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.ProjectionCommand;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.ReceiptResult;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.SpawnAttempt;
import com.alechilles.alecstamework.items.HytaleCompanionProjectionSpawnExecutor.SpawnStatus;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.logging.Level;
import org.joml.Vector3d;

/** Hytale ECS bridge used only after the shared executor has chosen receipt resolution or spawn. */
final class HytaleCompanionProjectionAttemptGateway implements AttemptGateway {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final World world;
    private final Store<EntityStore> store;
    private final PlannedNpcProjectionSpawner spawner;
    private final PlannedNpcProjectionPostAddService postAdd;

    HytaleCompanionProjectionAttemptGateway(
            World world,
            Store<EntityStore> store,
            PlannedNpcProjectionSpawner spawner,
            PlannedNpcProjectionPostAddService postAdd
    ) {
        this.world = world;
        this.store = store;
        this.spawner = spawner;
        this.postAdd = postAdd;
    }

    @Override
    public ReceiptResult probe(
            ProjectionCommand command,
            TameworkProjectionIdentityComponent expectedMarker
    ) {
        Ref<EntityStore> reference =
                world.getEntityRef(command.targetAlias().value());
        if (reference == null || !reference.isValid()) {
            return ReceiptResult.absent();
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (uuidType == null || npcType == null || markerType == null) {
            return ReceiptResult.conflict(null);
        }
        UUIDComponent identity = store.getComponent(reference, uuidType);
        NPCEntity npc = store.getComponent(reference, npcType);
        TameworkProjectionIdentityComponent marker =
                store.getComponent(reference, markerType);
        return HytaleCompanionProjectionSpawnExecutor.receiptMatches(
                command.targetAlias().value(),
                identity == null ? null : identity.getUuid(),
                npc,
                expectedMarker,
                marker
        )
                ? ReceiptResult.match()
                : ReceiptResult.conflict(null);
    }

    @Override
    public SpawnAttempt spawn(
            ProjectionCommand command,
            CoopResidentStateSnapshot snapshot,
            TameworkProjectionIdentityComponent marker
    ) {
        CompanionSpawnPlacement placement = command.placement();
        PlannedNpcProjectionSpawner.SpawnResult result = spawner.spawn(
                new PlannedNpcProjectionSpawner.SpawnRequest(
                        snapshot.roleId(),
                        command.targetAlias().value(),
                        snapshot,
                        marker,
                        new Vector3d(
                                placement.x(),
                                placement.y(),
                                placement.z()
                        ),
                        new Rotation3f(
                                placement.pitchRadians(),
                                placement.yawRadians(),
                                placement.rollRadians()
                        ),
                        store
                )
        );
        if (result == null || result.status() == null) {
            return SpawnAttempt.failed(SpawnStatus.SPAWN_FAILED, null);
        }
        SpawnStatus status = SpawnStatus.valueOf(result.status().name());
        if (status != SpawnStatus.SPAWNED) {
            return SpawnAttempt.failed(status, null);
        }
        applySpawnSafetyBestEffort(command, snapshot, result);
        applyPostAddBestEffort(command, result);
        return SpawnAttempt.spawned();
    }

    private void applySpawnSafetyBestEffort(
            ProjectionCommand command,
            CoopResidentStateSnapshot snapshot,
            PlannedNpcProjectionSpawner.SpawnResult result
    ) {
        try {
            CommandCompanionSpawnPhysicsResetService
                    .resetSpawnedCompanionPhysics(
                            result.reference(), result.npc(), store);
        } catch (RuntimeException | LinkageError failure) {
            logFollowUpFailure(command, "spawn_physics_reset", failure);
        }
        try {
            RecentSpawnProtectionService.getInstance().record(
                    command.targetAlias().value(),
                    command.operationCode(),
                    snapshot.roleId(),
                    System.currentTimeMillis()
            );
        } catch (RuntimeException | LinkageError failure) {
            logFollowUpFailure(command, "spawn_protection", failure);
        }
    }

    private void applyPostAddBestEffort(
            ProjectionCommand command,
            PlannedNpcProjectionSpawner.SpawnResult result
    ) {
        if (result.reference() == null || result.npc() == null
                || result.postAddWork() == null) {
            return;
        }
        try {
            postAdd.apply(
                    world,
                    result.reference(),
                    result.npc(),
                    store,
                    result.postAddWork()
            );
        } catch (RuntimeException | LinkageError failure) {
            logFollowUpFailure(command, "presentation", failure);
        }
    }

    private void logFollowUpFailure(
            ProjectionCommand command,
            String stage,
            Throwable failure
    ) {
        LOGGER.at(Level.WARNING).log(
                "Projection follow-up could not be applied after durable "
                        + "state insertion: stage=" + stage
                        + " operation=" + command.operationId()
                        + " profile=" + command.profileId()
                        + " failure=" + failure.getClass().getSimpleName()
        );
    }
}
