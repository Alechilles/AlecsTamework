package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSnapshotRoleResolver;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionWorld;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionLocalProjectionLifecycle;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.items.BondedCompanionProjectionSpawnBoundary;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.persistence
        .TameworkFullStateSnapshotReader;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Hytale 0.5.7 world-thread gateway for exact bonded projection cleanup.
 *
 * <p>The gateway resolves only stable IDs, revalidates the complete durable
 * identity on the owning world thread, and never widens a failed lookup to a
 * role, name, owner, or proximity search.</p>
 */
public final class HytaleBondedCompanionWorldGateway implements
        BondedCompanionProjectionCleanupService.WorldGateway,
        BondedCompanionProjectionWorld,
        BondedCompanionLocalProjectionLifecycle.ObservationSource {
    private final TameworkFullStateSnapshotReader snapshots;
    private final BondedCompanionProjectionSpawnBoundary spawns;

    public HytaleBondedCompanionWorldGateway() {
        this(new TameworkFullStateSnapshotReader(
                new CoopResidentStateSnapshotService()
        ), new BondedCompanionProjectionSpawnBoundary());
    }

    HytaleBondedCompanionWorldGateway(
            TameworkFullStateSnapshotReader snapshots
    ) {
        this(snapshots, new BondedCompanionProjectionSpawnBoundary());
    }

    HytaleBondedCompanionWorldGateway(
            TameworkFullStateSnapshotReader snapshots,
            BondedCompanionProjectionSpawnBoundary spawns
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.spawns = Objects.requireNonNull(spawns, "spawns");
    }

    /** Spawns the exact planned UUID at the caller-frozen world placement. */
    @Override
    public BondedCompanionProjectionService.SpawnResult spawn(
            @Nonnull BondedCompanionProjectionService.SpawnPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        if (plan.placement() == null
                || !plan.lease().worldKey().equals(
                        plan.placement().worldKey())) {
            return BondedCompanionProjectionService.SpawnResult.retryRequired();
        }
        try {
            World world = Universe.get().getWorld(plan.placement().worldKey());
            if (world == null) {
                return BondedCompanionProjectionService.SpawnResult
                        .retryRequired();
            }
            if (!world.isInThread()) {
                return BondedCompanionProjectionService.SpawnResult
                        .retryRequired();
            }
            return spawnOnWorldThread(world, plan);
        } catch (RuntimeException | LinkageError failure) {
            return BondedCompanionProjectionService.SpawnResult.failed();
        }
    }

    private BondedCompanionProjectionService.SpawnResult spawnOnWorldThread(
            World world,
            BondedCompanionProjectionService.SpawnPlan plan
    ) {
        if (!world.isInThread() || world.getEntityStore() == null
                || !world.getName().equals(plan.placement().worldKey())) {
            return BondedCompanionProjectionService.SpawnResult.retryRequired();
        }
        UUID plannedUuid = plan.lease().liveNpcUuid();
        var result = spawns.spawn(
                world, world.getEntityStore().getStore(),
                plan.lease().profileId(), plan.lease().leaseToken(),
                plannedUuid,
                plan.snapshot().fullState().npcUuid(),
                plan.placement(), plan.snapshot(), plan.summonAuraEffectId());
        return switch (result) {
            case CONFIRMED -> BondedCompanionProjectionService.SpawnResult
                    .spawned(plannedUuid);
            case RETRYABLE -> BondedCompanionProjectionService.SpawnResult
                    .retryRequired();
            case FAILED -> BondedCompanionProjectionService
                    .SpawnResult.failed();
        };
    }

    @Override
    public BondedCompanionProjectionValidator.Projection readExact(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        Objects.requireNonNull(lease, "lease");
        try {
            World world = Universe.get().getWorld(lease.worldKey());
            if (world == null) return null;
            return world.isInThread() ? readOnWorldThread(world, lease) : null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** Schedules one bounded inspection on only the supplied recorded world. */
    @Override
    @Nonnull
    public CompletionStage<BondedCompanionLocalProjectionLifecycle.WorldObservation>
    inspect(
            @Nonnull String worldKey,
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            int maximumResults
    ) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(leases, "leases");
        if (maximumResults < 1 || leases.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new BondedCompanionLocalProjectionLifecycle.WorldObservation(
                            List.of(), true));
        }
        World world;
        try {
            world = Universe.get().getWorld(worldKey);
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    BondedCompanionLocalProjectionLifecycle.WorldObservation
                            .inconclusive());
        }
        if (world == null || !worldKey.equals(world.getName())) {
            return CompletableFuture.completedFuture(
                    BondedCompanionLocalProjectionLifecycle.WorldObservation
                            .inconclusive());
        }
        Set<LeaseMarker> markers = markersFor(leases);
        CompletableFuture<BondedCompanionLocalProjectionLifecycle.WorldObservation>
                result = new CompletableFuture<>();
        Runnable read = () -> completeInspection(
                result, world, markers, maximumResults);
        try {
            if (world.isInThread()) read.run(); else world.execute(read);
        } catch (RuntimeException | LinkageError failure) {
            result.complete(BondedCompanionLocalProjectionLifecycle
                    .WorldObservation.inconclusive());
        }
        return result;
    }

    private void completeInspection(
            CompletableFuture<BondedCompanionLocalProjectionLifecycle.WorldObservation>
                    result,
            World world,
            Set<LeaseMarker> markers,
            int maximumResults
    ) {
        try {
            result.complete(scanOnWorldThread(world, markers, maximumResults));
        } catch (RuntimeException | LinkageError failure) {
            result.complete(BondedCompanionLocalProjectionLifecycle
                    .WorldObservation.inconclusive());
        }
    }

    private Set<LeaseMarker> markersFor(
            List<BondedCompanionProjectionValidator.LeaseExpectation> leases
    ) {
        Set<LeaseMarker> result = new HashSet<>();
        for (var lease : leases) {
            if (lease != null) {
                result.add(new LeaseMarker(lease.profileId(), lease.leaseToken()));
            }
        }
        return result;
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
            if (!world.isInThread()) {
                return BondedCompanionProjectionCleanupService.Outcome
                        .RETRY_REQUIRED;
            }
            return removeOnWorldThread(world, intent);
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
            return intent.target()
                    == BondedCompanionProjectionCleanupService.Target.PROJECTION
                    ? BondedCompanionProjectionCleanupService.Outcome.RETRY_REQUIRED
                    : BondedCompanionProjectionCleanupService.Outcome.ALREADY_MISSING;
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
        ExpiryDismountFallProtectionArmer.arm(world, store, reference, intent);
        store.removeEntity(reference, RemoveReason.REMOVE);
        return BondedCompanionProjectionCleanupService.Outcome.REMOVED;
    }

    private BondedCompanionLocalProjectionLifecycle.WorldObservation
    scanOnWorldThread(
            World world, Set<LeaseMarker> expectedMarkers, int maximumResults
    ) {
        if (!world.isInThread() || world.getEntityStore() == null
                || maximumResults < 1 || expectedMarkers.isEmpty()) {
            return BondedCompanionLocalProjectionLifecycle.WorldObservation
                    .inconclusive();
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                markerType = TameworkProjectionIdentityComponent
                        .getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        if (store == null || uuidType == null || markerType == null) {
            return BondedCompanionLocalProjectionLifecycle.WorldObservation
                    .inconclusive();
        }
        ArrayList<BondedCompanionProjectionValidator.Projection> found =
                new ArrayList<>();
        boolean[] complete = {true};
        store.forEachChunk(
                Query.and(uuidType, markerType),
                (ArchetypeChunk<EntityStore> chunk,
                        CommandBuffer<EntityStore> ignored) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        UUIDComponent uuid = chunk.getComponent(index, uuidType);
                        TameworkProjectionIdentityComponent marker =
                                chunk.getComponent(index, markerType);
                        String profileId = marker == null
                                ? null : marker.getProfileId();
                        String leaseToken = marker == null
                                ? null : marker.getBondedLeaseToken();
                        if (uuid == null || uuid.getUuid() == null || marker == null
                                || !marker.isBondedCompanion()
                                || profileId == null || leaseToken == null
                                || !expectedMarkers.contains(new LeaseMarker(
                                        profileId, leaseToken
                                ))) continue;
                        if (found.size() >= maximumResults) {
                            complete[0] = false;
                        } else {
                            Ref<EntityStore> reference = chunk.getReferenceTo(index);
                            NPCEntity npc = npcType == null ? null
                                    : chunk.getComponent(index, npcType);
                            BondedCompanionSnapshot snapshot = snapshot(
                                    reference, store, uuid.getUuid(), npc);
                            found.add(new BondedCompanionProjectionValidator.Projection(
                                    uuid.getUuid(), world.getName(), marker, snapshot
                            ));
                        }
                    }
                }
        );
        return new BondedCompanionLocalProjectionLifecycle.WorldObservation(
                found, complete[0]);
    }

    @Nullable
    private BondedCompanionSnapshot snapshot(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            UUID npcUuid,
            NPCEntity npc
    ) {
        if (reference == null || !reference.isValid() || npc == null) return null;
        String snapshotRoleId = AvatarFlightSnapshotRoleResolver.resolve(
                npc.getRoleName(), reference, store
        );
        var captured = snapshots.readSourceNeutral(
                reference, store, new NpcAlias(npcUuid), snapshotRoleId);
        return captured.successful()
                ? BondedCompanionSnapshot.of(captured.snapshot(), Map.of())
                : null;
    }

    /** Captures direct current-world death evidence without a durable lease page. */
    @Nullable
    public BondedCompanionProjectionValidator.Projection readCurrent(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store,
            @Nonnull String worldKey,
            @Nonnull UUID npcUuid,
            @Nonnull TameworkProjectionIdentityComponent suppliedMarker
    ) {
        if (!reference.isValid() || reference.getStore() != store) return null;
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (uuidType == null || markerType == null || npcType == null) return null;
        UUIDComponent actualUuid = store.getComponent(reference, uuidType);
        TameworkProjectionIdentityComponent actualMarker =
                store.getComponent(reference, markerType);
        NPCEntity npc = store.getComponent(reference, npcType);
        if (actualUuid == null || !npcUuid.equals(actualUuid.getUuid())
                || actualMarker == null || !actualMarker.isBondedCompanion()
                || !Objects.equals(suppliedMarker.getProfileId(),
                actualMarker.getProfileId())
                || !Objects.equals(suppliedMarker.getBondedLeaseToken(),
                actualMarker.getBondedLeaseToken())) return null;
        return new BondedCompanionProjectionValidator.Projection(
                npcUuid, worldKey, actualMarker,
                snapshot(reference, store, npcUuid, npc));
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
                AvatarFlightSnapshotRoleResolver.resolve(
                        npc.getRoleName(), reference, store
                )
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

    /** Stable durable marker key used to restrict a bounded entity-store scan. */
    private record LeaseMarker(String profileId, String leaseToken) {
        private LeaseMarker {
            profileId = Objects.requireNonNull(profileId, "profileId");
            leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
        }
    }

}
