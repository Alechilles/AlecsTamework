package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpoint;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCapture;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCaptureService;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointSink;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Shared in-memory cache of immutable last-live companion snapshots.
 *
 * <p>The cache has one representation for command links, profile observation,
 * and removal recovery. Mutable ECS components never leave snapshot capture.</p>
 */
public final class CommandLinkedNpcStateSnapshotService {
    private final ConcurrentHashMap<UUID, LiveLinkedNpcSnapshot> snapshotsByNpc =
            new ConcurrentHashMap<>();
    private final CommandLiveNpcSnapshotFactory snapshotFactory =
            new CommandLiveNpcSnapshotFactory();
    private final CompanionProfileSnapshotSink profileSnapshots;
    private final LoadedNpcIdentityIndex loadedNpcIdentityIndex;
    private final CompanionEntityCheckpointCaptureService checkpointCaptures;
    private final ConcurrentHashMap<UUID, Long> newestRoutineCheckpoints =
            new ConcurrentHashMap<>();
    private final AtomicLong checkpointSequence = new AtomicLong();

    public CommandLinkedNpcStateSnapshotService() {
        this(
                CompanionProfileSnapshotSink.ignore(),
                new LoadedNpcIdentityIndex(),
                CompanionEntityCheckpointSink.IGNORE
        );
    }

    public CommandLinkedNpcStateSnapshotService(
            @Nonnull CompanionProfileSnapshotSink profileSnapshots
    ) {
        this(
                profileSnapshots,
                new LoadedNpcIdentityIndex(),
                CompanionEntityCheckpointSink.IGNORE
        );
    }

    public CommandLinkedNpcStateSnapshotService(
            @Nonnull CompanionProfileSnapshotSink profileSnapshots,
            @Nonnull LoadedNpcIdentityIndex loadedNpcIdentityIndex
    ) {
        this(
                profileSnapshots,
                loadedNpcIdentityIndex,
                CompanionEntityCheckpointSink.IGNORE
        );
    }

    public CommandLinkedNpcStateSnapshotService(
            @Nonnull CompanionProfileSnapshotSink profileSnapshots,
            @Nonnull LoadedNpcIdentityIndex loadedNpcIdentityIndex,
            @Nonnull CompanionEntityCheckpointSink checkpointSink
    ) {
        this.profileSnapshots = Objects.requireNonNull(
                profileSnapshots, "profileSnapshots"
        );
        this.loadedNpcIdentityIndex = Objects.requireNonNull(loadedNpcIdentityIndex, "loadedNpcIdentityIndex");
        this.checkpointCaptures =
                new CompanionEntityCheckpointCaptureService(
                        Objects.requireNonNull(checkpointSink, "checkpointSink"),
                        System::currentTimeMillis
                );
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        indexNpcAdded(reference, store);
        CompletionStage<Void> profile = refreshFromEntityStage(
                reference, store
        );
        CompanionEntityCheckpointCapture checkpoint = checkpointCaptures.capture(
                reference,
                store,
                CompanionEntityCheckpoint.CaptureBoundary.LOADED
        );
        publishCheckpointAfterProfile(profile, checkpoint);
    }

    public void onNpcRemoved(Ref<EntityStore> reference,
                             RemoveReason reason,
                             Store<EntityStore> store) {
        UUID npcUuid = beginNpcRemoval(reference, reason, store);
        completeNpcRemoval(reference, reason, store, npcUuid);
    }

    /**
     * Refreshes the final linked state and removes live-identity evidence while retaining that state
     * until all removal observers have classified the disappearance.
     */
    @Nullable
    public UUID beginNpcRemoval(Ref<EntityStore> reference,
                                RemoveReason reason,
                                Store<EntityStore> store) {
        if (reference == null || store == null) {
            return null;
        }
        if (reason == RemoveReason.REMOVE || reason == RemoveReason.UNLOAD) {
            CompletionStage<Void> profile = refreshFromEntityStage(
                    reference, store
            );
            CompanionEntityCheckpointCapture checkpoint =
                    checkpointCaptures.capture(
                            reference,
                            store,
                            reason == RemoveReason.UNLOAD
                                    ? CompanionEntityCheckpoint.CaptureBoundary
                                    .UNLOAD
                                    : CompanionEntityCheckpoint.CaptureBoundary
                                    .DESTRUCTIVE_REMOVE
                    );
            publishCheckpointAfterProfile(profile, checkpoint);
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID componentUuid = resolveComponentUuid(reference, store);
        UUID legacyNpcUuid = npc != null ? npc.getUuid() : null;
        LoadedNpcIdentityIndex.Location location = LoadedNpcLocationResolver.resolve(store);
        LoadedNpcIdentityIndex.LoadedNpcObservation observation = observation(
                reference, store, componentUuid, legacyNpcUuid, location
        );
        if (observation != null) {
            loadedNpcIdentityIndex.recordRemoved(observation);
        }
        UUID indexedUuid = componentUuid != null ? componentUuid : legacyNpcUuid;
        return npc != null && npc.getUuid() != null ? npc.getUuid() : indexedUuid;
    }

    /** Clears destructive-removal state only after death/lost observers have consumed the boundary snapshot. */
    public void completeNpcRemoval(Ref<EntityStore> reference,
                                   RemoveReason reason,
                                   Store<EntityStore> store,
                                   @Nullable UUID npcUuid) {
        if (reference == null || store == null || npcUuid == null) {
            return;
        }
        if (reason == RemoveReason.REMOVE) {
            snapshotsByNpc.remove(npcUuid);
            return;
        }
        refreshFromEntity(reference, store);
    }

    @Nonnull
    public LoadedNpcIdentityIndex getLoadedNpcIdentityIndex() {
        return loadedNpcIdentityIndex;
    }

    /**
     * Retires only the exact entity-store identity of a removed world.
     *
     * <p>The caller must run from an uncancelled, terminal-priority {@code RemoveWorldEvent}.
     * Full state snapshots intentionally remain available for later Lost recovery after the
     * store's live identity evidence is withdrawn. The result indicates whether the removed
     * world is delete-on-remove and therefore needs immediate terminal recovery.</p>
     */
    public boolean retireRemovedWorld(@Nullable World world) {
        if (world == null || world.getEntityStore() == null
                || world.getEntityStore().getStore() == null) {
            return false;
        }
        loadedNpcIdentityIndex.clearLocation(
                LoadedNpcLocationResolver.resolve(world.getEntityStore().getStore())
        );
        return world.getWorldConfig() != null && world.getWorldConfig().isDeleteOnRemove();
    }

    private void indexNpcAdded(@Nonnull Ref<EntityStore> reference,
                               @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        UUID componentUuid = resolveComponentUuid(reference, store);
        UUID legacyNpcUuid = npc.getUuid();
        LoadedNpcIdentityIndex.Location location = LoadedNpcLocationResolver.resolve(store);
        LoadedNpcIdentityIndex.LoadedNpcObservation observation = observation(
                reference, store, componentUuid, legacyNpcUuid, location
        );
        if (observation != null) {
            loadedNpcIdentityIndex.recordAdded(observation);
        }
    }

    @Nullable
    private LoadedNpcIdentityIndex.LoadedNpcObservation observation(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store,
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid,
            @Nonnull LoadedNpcIdentityIndex.Location location) {
        if (componentUuid == null && legacyNpcUuid == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        TameworkProjectionIdentityComponent marker = markerType != null
                ? store.getComponent(reference, markerType) : null;
        return new LoadedNpcIdentityIndex.LoadedNpcObservation(
                componentUuid,
                legacyNpcUuid,
                location,
                projectionKey(marker)
        );
    }

    @Nullable
    static LoadedNpcIdentityIndex.ProjectionKey projectionKey(
            @Nullable TameworkProjectionIdentityComponent marker) {
        if (marker == null || marker.getProfileId() == null || marker.getProfileId().isBlank()
                || marker.getOperationId() == null || marker.getOperationId().isBlank()
                || marker.getProjectionKind() == null || marker.getProjectionKind().isBlank()
                || marker.getGeneration() < 0L) {
            return null;
        }
        return new LoadedNpcIdentityIndex.ProjectionKey(
                marker.getProfileId(),
                marker.getOperationId(),
                marker.getProjectionKind(),
                marker.getSlotKey(),
                marker.getSourceNpcUuid(),
                marker.getGeneration()
        );
    }

    @Nullable
    private UUID resolveComponentUuid(@Nonnull Ref<EntityStore> reference,
                                      @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        UUIDComponent uuidComponent = uuidType != null ? store.getComponent(reference, uuidType) : null;
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    public void refreshFromEntity(Ref<EntityStore> reference, Store<EntityStore> store) {
        refreshFromEntityStage(reference, store);
    }

    private CompletionStage<Void> refreshFromEntityStage(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        if (reference == null || !reference.isValid() || store == null) {
            return CompletableFuture.completedFuture(null);
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        LiveLinkedNpcSnapshot snapshot =
                snapshotFactory.capture(
                        reference, store, npc, snapshotsByNpc.get(npcUuid));
        if (snapshot == null) {
            snapshotsByNpc.remove(npcUuid);
            return CompletableFuture.completedFuture(null);
        }
        snapshotsByNpc.put(npcUuid, snapshot);
        if (!hasProjectionIdentity(reference, store)) {
            return upsertProfile(snapshot, worldKey(store));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Nullable
    public LiveLinkedNpcSnapshot getSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return snapshotsByNpc.get(npcUuid);
    }

    public void clearSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        snapshotsByNpc.remove(npcUuid);
    }

    private boolean hasProjectionIdentity(@Nonnull Ref<EntityStore> reference,
                                          @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (markerType == null) {
            return false;
        }
        return shouldDeferProfileUpsert(store.getComponent(reference, markerType));
    }

    static boolean shouldDeferProfileUpsert(@Nullable TameworkProjectionIdentityComponent marker) {
        if (marker == null || marker.getProfileId() == null || marker.getProfileId().isBlank()
                || marker.getOperationId() == null || marker.getOperationId().isBlank()) {
            return false;
        }
        String kind = marker.getProjectionKind();
        return TameworkProjectionIdentityComponent.KIND_RECOVERY.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER.equals(kind);
    }

    private CompletionStage<Void> upsertProfile(
            @Nonnull LiveLinkedNpcSnapshot snapshot,
            @Nullable String worldKey
    ) {
        if (snapshot.npcUuid() == null || worldKey == null) {
            return CompletableFuture.completedFuture(null);
        }
        return profileSnapshots.publish(snapshot, worldKey);
    }

    /**
     * Sequences immutable checkpoint data after its profile publication.
     * Only the newest routine observation may enter checkpoint admission.
     */
    void publishCheckpointAfterProfile(
            @Nonnull CompletionStage<Void> profilePublication,
            @Nullable CompanionEntityCheckpointCapture checkpoint
    ) {
        Objects.requireNonNull(profilePublication, "profilePublication");
        if (checkpoint == null) {
            return;
        }
        UUID alias = checkpoint.alias().value();
        boolean routine = checkpoint.boundary()
                == CompanionEntityCheckpoint.CaptureBoundary.LOADED;
        long token = checkpointSequence.incrementAndGet();
        if (routine) {
            newestRoutineCheckpoints.put(alias, token);
        }
        CompletionStage<Void> chained = publishCheckpointAfterProfile(
                profilePublication,
                checkpoint,
                checkpointCaptures::publish,
                () -> !routine || Objects.equals(
                        newestRoutineCheckpoints.get(alias), token
                )
        );
        chained.whenComplete((ignored, failure) -> {
            if (routine) {
                newestRoutineCheckpoints.remove(alias, token);
            }
        });
    }

    /** Sequences one immutable checkpoint behind a successful profile stage. */
    static CompletableFuture<Void> publishCheckpointAfterProfile(
            CompletionStage<Void> profilePublication,
            CompanionEntityCheckpointCapture checkpoint,
            Function<CompanionEntityCheckpointCapture,
                    CompletionStage<Void>> publisher,
            BooleanSupplier stillCurrent
    ) {
        Objects.requireNonNull(profilePublication, "profilePublication");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        return profilePublication.thenCompose(ignored -> {
            if (!stillCurrent.getAsBoolean()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletionStage<Void> publication = publisher.apply(checkpoint);
            return Objects.requireNonNull(
                    publication, "Checkpoint publisher returned null"
            );
        }).toCompletableFuture();
    }

    @Nullable
    private String worldKey(Store<EntityStore> store) {
        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        String name = world == null ? null : world.getName();
        return name == null || name.isBlank() ? null : name.trim();
    }

    /**
     * Immutable last-live state used by command links and profile observation.
     *
     * <p>Full companion gameplay state belongs to canonical profile snapshots;
     * this cache deliberately retains only the fields its live readers use.</p>
     */
    public record LiveLinkedNpcSnapshot(
            @Nonnull UUID npcUuid,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            @Nonnull String[] toolIds,
            @Nullable String roleId,
            boolean tamed,
            @Nullable String customName,
            @Nullable String displayName,
            @Nullable Vector3d lastKnownPosition,
            @Nullable Vector3d homePosition
    ) {
        public LiveLinkedNpcSnapshot {
            Objects.requireNonNull(npcUuid, "npcUuid");
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
            lastKnownPosition = lastKnownPosition == null
                    ? null : new Vector3d(lastKnownPosition);
            homePosition = homePosition == null
                    ? null : new Vector3d(homePosition);
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }

        @Override
        public Vector3d lastKnownPosition() {
            return lastKnownPosition == null
                    ? null : new Vector3d(lastKnownPosition);
        }

        @Override
        public Vector3d homePosition() {
            return homePosition == null
                    ? null : new Vector3d(homePosition);
        }
    }
}
