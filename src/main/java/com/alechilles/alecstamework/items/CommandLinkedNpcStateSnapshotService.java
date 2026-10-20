package com.alechilles.alecstamework.items;

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

    public CommandLinkedNpcStateSnapshotService() {
        this(CompanionProfileSnapshotSink.ignore(), new LoadedNpcIdentityIndex());
    }

    public CommandLinkedNpcStateSnapshotService(
            @Nonnull CompanionProfileSnapshotSink profileSnapshots
    ) {
        this(profileSnapshots, new LoadedNpcIdentityIndex());
    }

    public CommandLinkedNpcStateSnapshotService(
            @Nonnull CompanionProfileSnapshotSink profileSnapshots,
            @Nonnull LoadedNpcIdentityIndex loadedNpcIdentityIndex
    ) {
        this.profileSnapshots = Objects.requireNonNull(
                profileSnapshots, "profileSnapshots"
        );
        this.loadedNpcIdentityIndex = Objects.requireNonNull(loadedNpcIdentityIndex, "loadedNpcIdentityIndex");
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        indexNpcAdded(reference, store);
        refreshFromEntityStage(reference, store);
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
            refreshFromEntityStage(reference, store);
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
        // Retain the final presentation refresh for the unload boundary.
        refreshFromEntityStage(reference, store);
    }

    @Nonnull
    public LoadedNpcIdentityIndex getLoadedNpcIdentityIndex() {
        return loadedNpcIdentityIndex;
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
        return new LoadedNpcIdentityIndex.LoadedNpcObservation(componentUuid, legacyNpcUuid, location);
    }

    @Nullable
    private UUID resolveComponentUuid(@Nonnull Ref<EntityStore> reference,
                                      @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        UUIDComponent uuidComponent = uuidType != null ? store.getComponent(reference, uuidType) : null;
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    public void refreshFromEntity(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        refreshFromEntityStage(reference, store);
    }

    /**
     * Freezes linked profile facts on the world thread and returns their publication completion.
     * Terminal lifecycle authors await this before reading a newly linked companion's profile.
     */
    @Nonnull
    public CompletionStage<Void> refreshFromEntityStage(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store
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
        return upsertProfile(snapshot, worldKey(store));
    }

    @Nullable
    public LiveLinkedNpcSnapshot getSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return snapshotsByNpc.get(npcUuid);
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
