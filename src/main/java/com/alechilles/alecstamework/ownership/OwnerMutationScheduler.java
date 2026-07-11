package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Defers owner mutations whose caller cannot wait for SQLite without blocking a world thread.
 */
public final class OwnerMutationScheduler {
    private final OwnerPopulationIndex index;
    private final CompanionIdentityResolver identityResolver;
    private final OwnerPopulationAdmissionCoordinator coordinator;
    private final OwnerComponentMutationService mutationService;

    public OwnerMutationScheduler(@Nonnull OwnerPopulationIndex index,
                                  @Nonnull CompanionIdentityResolver identityResolver,
                                  @Nonnull OwnerPopulationAdmissionCoordinator coordinator,
                                  @Nonnull OwnerComponentMutationService mutationService) {
        this.index = Objects.requireNonNull(index, "index");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
    }

    /**
     * Snapshots live state and starts durable preparation without blocking the current world.
     */
    public boolean schedule(@Nonnull Ref<EntityStore> npcRef,
                            @Nonnull Store<EntityStore> store,
                            @Nullable UUID newOwnerId,
                            @Nullable String newOwnerName,
                            @Nonnull CompanionLifecycleState lifecycleState,
                            @Nonnull OwnerPopulationOperation operation,
                            boolean force,
                            @Nonnull String idempotencyKey,
                            @Nullable MutationCallbacks callbacks) {
        MutationCallbacks safeCallbacks = callbacks == null ? MutationCallbacks.NOOP : callbacks;
        Snapshot snapshot = snapshot(npcRef, store, idempotencyKey);
        if (snapshot == null) {
            safeCallbacks.onDenied("owner-mutation-snapshot-unavailable", null);
            return false;
        }
        OwnerPopulationEntry current = index.entry(snapshot.profileId()).orElse(null);
        if (current == null && snapshot.oldOwnerId() != null) {
            current = new OwnerPopulationEntry(
                    snapshot.profileId(),
                    snapshot.oldOwnerId(),
                    snapshot.worldName(),
                    CompanionLifecycleState.UNKNOWN_DORMANT,
                    0L
            );
            index.reconcileCommittedEntry(current);
        }

        Policy policy = resolvePolicy();
        OwnerPopulationTransitionRequest transition = transition(
                snapshot,
                current,
                newOwnerId,
                lifecycleState,
                operation,
                policy,
                force
        );
        long revision = current == null ? 0L : current.revision();
        CompanionPopulationStateRecord baseline = baseline(snapshot, current, revision);
        long capturedSettingsRevision = policy.settingsRevision();
        OwnerPopulationAdmissionPlan plan = new OwnerPopulationAdmissionPlan(
                transition,
                baseline,
                snapshot.npcUuid(),
                snapshot.worldName(),
                snapshot.chunkX(),
                snapshot.chunkZ(),
                operation.name().toLowerCase(java.util.Locale.ROOT),
                ownerJson(snapshot.oldOwnerId()),
                ownerJson(newOwnerId),
                contextJson(snapshot),
                capturedSettingsRevision,
                ClaimProviderGeneration.NONE
        );
        coordinator.prepareAsync(plan).whenComplete((preparation, failure) ->
                dispatchPrepared(
                        snapshot.world(),
                        snapshot.npcUuid(),
                        newOwnerId,
                        newOwnerName,
                        safeCallbacks,
                        preparation,
                        failure
                )
        );
        return true;
    }

    private void dispatchPrepared(@Nonnull World world,
                                  @Nonnull UUID npcUuid,
                                  @Nullable UUID newOwnerId,
                                  @Nullable String newOwnerName,
                                  @Nonnull MutationCallbacks callbacks,
                                  @Nullable OwnerPopulationPreparationResult preparation,
                                  @Nullable Throwable failure) {
        if (failure != null || preparation == null || !preparation.allowed()) {
            String reason = failure == null && preparation != null
                    ? preparation.reason()
                    : "owner-mutation-prepare-failed";
            executeSafely(world, () -> callbacks.onDenied(
                    reason,
                    preparation == null ? null : preparation.decision()
            ));
            return;
        }
        PreparedOwnerPopulationAdmission prepared = preparation.preparedAdmission();
        executeSafely(world, () -> applyPrepared(
                world,
                npcUuid,
                newOwnerId,
                newOwnerName,
                callbacks,
                prepared
        ), () -> coordinator.cancelAsync(prepared, "owner-mutation-world-unavailable"));
    }

    private void applyPrepared(@Nonnull World world,
                               @Nonnull UUID npcUuid,
                               @Nullable UUID newOwnerId,
                               @Nullable String newOwnerName,
                               @Nonnull MutationCallbacks callbacks,
                               @Nonnull PreparedOwnerPopulationAdmission prepared) {
        Ref<EntityStore> liveRef = world.getEntityRef(npcUuid);
        Store<EntityStore> liveStore = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (liveRef == null || !liveRef.isValid() || liveStore == null) {
            coordinator.cancelAsync(prepared, "owner-mutation-target-unavailable");
            callbacks.onDenied("owner-mutation-target-unavailable", prepared.decision());
            return;
        }
        OwnerComponentMutationService.MutationResult result = mutationService.applyImmediate(
                liveRef,
                liveStore,
                prepared,
                newOwnerId,
                newOwnerName,
                resolvePolicy().settingsRevision(),
                ClaimProviderGeneration.NONE
        );
        if (!result.applied() || result.completion() == null) {
            callbacks.onDenied(result.reason(), prepared.decision());
            return;
        }
        callbacks.onApplied(prepared.decision());
        result.completion().whenComplete((commit, failure) ->
                executeSafely(world, () -> {
                    if (failure != null || commit == null || !commit.committed()) {
                        callbacks.onDurabilityDegraded(
                                commit == null ? "owner-mutation-finalize-failed" : commit.reason()
                        );
                    } else {
                        identityResolver.markDurable(
                                prepared.plan().transition().profileId(),
                                npcUuid
                        );
                        callbacks.onCommitted(commit);
                    }
                })
        );
    }

    @Nullable
    private Snapshot snapshot(@Nonnull Ref<EntityStore> npcRef,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull String idempotencyKey) {
        if (!npcRef.isValid() || store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (uuidType == null || transformType == null || ownerType == null) {
            return null;
        }
        UUIDComponent uuid = store.getComponent(npcRef, uuidType);
        if (uuid == null || uuid.getUuid() == null) {
            return null;
        }
        TransformComponent transform = store.getComponent(npcRef, transformType);
        Vector3d position = transform == null ? null : transform.getPosition();
        if (position == null) {
            return null;
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
        UUID ownerId = owner == null ? null : owner.getOwnerId();
        String ownerName = owner == null ? null : owner.getOwnerName();
        CompanionIdentityResolver.Resolution identity = identityResolver.resolveOrAllocate(
                uuid.getUuid(),
                idempotencyKey
        );
        return new Snapshot(
                world,
                world.getName().trim(),
                uuid.getUuid(),
                identity.profileId(),
                ownerId,
                ownerName,
                ChunkUtil.chunkCoordinate((int) Math.floor(position.x)),
                ChunkUtil.chunkCoordinate((int) Math.floor(position.z))
        );
    }

    @Nonnull
    private static OwnerPopulationTransitionRequest transition(
            @Nonnull Snapshot snapshot,
            @Nullable OwnerPopulationEntry current,
            @Nullable UUID newOwnerId,
            @Nonnull CompanionLifecycleState lifecycleState,
            @Nonnull OwnerPopulationOperation operation,
            @Nonnull Policy policy,
            boolean force
    ) {
        return new OwnerPopulationTransitionRequest(
                snapshot.profileId(),
                current == null
                        ? OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION
                        : current.revision(),
                current == null ? null : current.ownerId(),
                current == null ? null : current.ownershipWorldName(),
                newOwnerId,
                snapshot.worldName(),
                lifecycleState,
                operation,
                policy.scope(),
                policy.limit(),
                force
        );
    }

    @Nonnull
    private static CompanionPopulationStateRecord baseline(
            @Nonnull Snapshot snapshot,
            @Nullable OwnerPopulationEntry current,
            long revision
    ) {
        long now = System.currentTimeMillis();
        return new CompanionPopulationStateRecord(
                snapshot.profileId(),
                snapshot.npcUuid(),
                current == null ? null : current.ownerId(),
                snapshot.worldName(),
                current == null ? snapshot.worldName() : current.ownershipWorldName(),
                current == null
                        ? CompanionLifecycleState.ACTIVE.name()
                        : current.lifecycleState().name(),
                snapshot.worldName(),
                snapshot.chunkX(),
                snapshot.chunkZ(),
                revision,
                "owner_mutation_snapshot",
                now,
                now
        );
    }

    @Nonnull
    private static Policy resolvePolicy() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        if (config == null) {
            config = TwGlobalConfig.defaultConfig();
        }
        TameworkRuntimeSettings runtime = TameworkRuntimeSettings.currentOrNull();
        int limit = runtime == null
                ? config.getPopulationLimitPerPlayerOwnedTotal()
                : runtime.populationLimitPerPlayerOwnedTotal();
        TwGlobalConfig.PerPlayerLimitScope configuredScope = runtime == null
                ? config.getPopulationPerPlayerLimitScope()
                : TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(
                        runtime.populationPerPlayerLimitScope()
                );
        OwnerPopulationLimitScope scope = configuredScope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                ? OwnerPopulationLimitScope.GLOBAL
                : OwnerPopulationLimitScope.PER_WORLD;
        long revision = runtime == null
                ? fallbackRevision(limit, scope)
                : runtime.revision();
        return new Policy(limit, scope, revision);
    }

    private static long fallbackRevision(int limit, @Nonnull OwnerPopulationLimitScope scope) {
        long mixed = 31L * Math.max(0, limit) + scope.ordinal();
        return mixed & Long.MAX_VALUE;
    }

    @Nonnull
    private static String ownerJson(@Nullable UUID ownerId) {
        JsonObject json = new JsonObject();
        if (ownerId == null) {
            json.add("ownerUuid", null);
        } else {
            json.addProperty("ownerUuid", ownerId.toString());
        }
        return json.toString();
    }

    @Nonnull
    private static String contextJson(@Nonnull Snapshot snapshot) {
        JsonObject json = new JsonObject();
        json.addProperty("world", snapshot.worldName());
        json.addProperty("chunkX", snapshot.chunkX());
        json.addProperty("chunkZ", snapshot.chunkZ());
        json.addProperty("npcUuid", snapshot.npcUuid().toString());
        return json.toString();
    }

    private static void executeSafely(@Nonnull World world, @Nonnull Runnable task) {
        executeSafely(world, task, () -> {
        });
    }

    private static void executeSafely(@Nonnull World world,
                                      @Nonnull Runnable task,
                                      @Nonnull Runnable rejected) {
        try {
            if (!world.isAlive()) {
                rejected.run();
                return;
            }
            world.execute(task);
        } catch (RuntimeException | LinkageError exception) {
            rejected.run();
        }
    }

    public interface MutationCallbacks {
        MutationCallbacks NOOP = new MutationCallbacks() {
        };

        default void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
        }

        default void onApplied(@Nonnull OwnerPopulationDecision decision) {
        }

        default void onCommitted(@Nonnull OwnerPopulationCommitResult result) {
        }

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }

    private record Policy(int limit,
                          @Nonnull OwnerPopulationLimitScope scope,
                          long settingsRevision) {
    }

    private record Snapshot(@Nonnull World world,
                            @Nonnull String worldName,
                            @Nonnull UUID npcUuid,
                            @Nonnull String profileId,
                            @Nullable UUID oldOwnerId,
                            @Nullable String oldOwnerName,
                            int chunkX,
                            int chunkZ) {
    }
}
