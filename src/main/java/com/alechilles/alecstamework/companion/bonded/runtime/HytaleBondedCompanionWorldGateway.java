package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.persistence
        .TameworkFullStateSnapshotReader;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Hytale 0.5.7 world-thread gateway for exact bonded projection cleanup.
 *
 * <p>The gateway resolves only stable IDs, revalidates the complete durable
 * identity on the owning world thread, and never widens a failed lookup to a
 * role, name, owner, or proximity search.</p>
 */
public final class HytaleBondedCompanionWorldGateway implements
        BondedCompanionProjectionCleanupService.WorldGateway,
        BondedCompanionProjectionService.World {
    private final TameworkFullStateSnapshotReader snapshots;

    public HytaleBondedCompanionWorldGateway() {
        this(new TameworkFullStateSnapshotReader(
                new CoopResidentStateSnapshotService()
        ));
    }

    HytaleBondedCompanionWorldGateway(
            TameworkFullStateSnapshotReader snapshots
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /** Placement is not present at this layer, so spawning remains fail-closed. */
    @Override
    public BondedCompanionProjectionService.SpawnResult spawn(
            @Nonnull BondedCompanionProjectionService.SpawnPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        return BondedCompanionProjectionService.SpawnResult.retryRequired();
    }

    @Override
    public BondedCompanionProjectionValidator.Projection readExact(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        Objects.requireNonNull(lease, "lease");
        try {
            World world = Universe.get().getWorld(lease.worldKey());
            if (world == null) return null;
            return world.isInThread()
                    ? readOnWorldThread(world, lease)
                    : CompletableFuture.supplyAsync(
                            () -> readOnWorldThread(world, lease), world
                    ).join();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    @Override
    @Nonnull
    public BondedCompanionProjectionCleanupService.Outcome removeIfExact(
            @Nonnull BondedCompanionProjectionCleanupService.CleanupIntent intent
    ) {
        Objects.requireNonNull(intent, "intent");
        try {
            World world = Universe.get().getWorld(intent.worldKey());
            if (world == null) {
                return BondedCompanionProjectionCleanupService.Outcome
                        .RETRY_REQUIRED;
            }
            if (world.isInThread()) {
                return removeOnWorldThread(world, intent);
            }
            return CompletableFuture.supplyAsync(
                    () -> removeOnWorldThread(world, intent),
                    world
            ).join();
        } catch (RuntimeException | LinkageError failure) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .RETRY_REQUIRED;
        }
    }

    private BondedCompanionProjectionCleanupService.Outcome
    removeOnWorldThread(
            World world,
            BondedCompanionProjectionCleanupService.CleanupIntent intent
    ) {
        if (!world.isInThread() || !intent.worldKey().equals(world.getName())
                || world.getEntityStore() == null) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .RETRY_REQUIRED;
        }
        Ref<EntityStore> reference = world.getEntityRef(
                intent.targetNpcUuid()
        );
        if (reference == null || !reference.isValid()) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .ALREADY_MISSING;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (reference.getStore() != store) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .IDENTITY_MISMATCH;
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        if (uuidType == null) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .RETRY_REQUIRED;
        }
        UUIDComponent uuid = store.getComponent(reference, uuidType);
        if (uuid == null || !intent.targetNpcUuid().equals(uuid.getUuid())) {
            return BondedCompanionProjectionCleanupService.Outcome
                    .IDENTITY_MISMATCH;
        }
        if (intent.target()
                == BondedCompanionProjectionCleanupService.Target.PROJECTION) {
            ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                    markerType = TameworkProjectionIdentityComponent
                            .getComponentType();
            if (markerType == null) {
                return BondedCompanionProjectionCleanupService.Outcome
                        .RETRY_REQUIRED;
            }
            TameworkProjectionIdentityComponent marker =
                    store.getComponent(reference, markerType);
            if (!matchesExactProjection(
                    intent, world.getName(), uuid.getUuid(), marker
            )) {
                return BondedCompanionProjectionCleanupService.Outcome
                        .IDENTITY_MISMATCH;
            }
        }
        store.removeEntity(reference, RemoveReason.REMOVE);
        return BondedCompanionProjectionCleanupService.Outcome.REMOVED;
    }

    private BondedCompanionProjectionValidator.Projection readOnWorldThread(
            World world,
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        if (!world.isInThread() || !lease.worldKey().equals(world.getName())
                || world.getEntityStore() == null) return null;
        Ref<EntityStore> reference = world.getEntityRef(lease.liveNpcUuid());
        if (reference == null || !reference.isValid()) return null;
        Store<EntityStore> store = world.getEntityStore().getStore();
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                markerType = TameworkProjectionIdentityComponent
                        .getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        if (uuidType == null || markerType == null || npcType == null
                || reference.getStore() != store) return null;
        UUIDComponent uuid = store.getComponent(reference, uuidType);
        TameworkProjectionIdentityComponent marker =
                store.getComponent(reference, markerType);
        NPCEntity npc = store.getComponent(reference, npcType);
        if (uuid == null || npc == null
                || !lease.liveNpcUuid().equals(uuid.getUuid())
                || !lease.profileId().equals(marker == null
                        ? null : marker.getProfileId())
                || !lease.leaseToken().equals(marker == null
                        ? null : marker.getBondedLeaseToken())) return null;
        var captured = snapshots.readSourceNeutral(
                reference, store, new NpcAlias(uuid.getUuid()),
                npc.getRoleName()
        );
        BondedCompanionSnapshot snapshot = captured.successful()
                ? BondedCompanionSnapshot.of(captured.snapshot(), Map.of())
                : null;
        return new BondedCompanionProjectionValidator.Projection(
                uuid.getUuid(), world.getName(), marker, snapshot
        );
    }

    /** Tests every durable projection identity field without fallback matching. */
    public static boolean matchesExactProjection(
            @Nonnull BondedCompanionProjectionCleanupService.CleanupIntent intent,
            String actualWorldKey,
            UUID actualNpcUuid,
            TameworkProjectionIdentityComponent marker
    ) {
        return intent.target()
                == BondedCompanionProjectionCleanupService.Target.PROJECTION
                && intent.worldKey().equals(actualWorldKey)
                && intent.targetNpcUuid().equals(actualNpcUuid)
                && marker != null
                && marker.matches(
                        TameworkProjectionIdentityComponent
                                .KIND_BONDED_COMPANION,
                        intent.leaseToken(),
                        intent.profileId()
                );
    }
}
