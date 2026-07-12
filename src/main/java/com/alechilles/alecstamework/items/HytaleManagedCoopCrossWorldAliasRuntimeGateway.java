package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirementCoordinator.ProjectionObservation;
import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirementCoordinator.RuntimeGateway;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.MarkerEvidence;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Hytale world-thread adapter for exact cross-world managed-coop alias proof and retirement. */
final class HytaleManagedCoopCrossWorldAliasRuntimeGateway implements RuntimeGateway {
    @Nullable
    private final ComponentType<EntityStore, NPCEntity> npcType;
    @Nullable
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    @Nullable
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType;
    private final WorldLookup worlds;

    HytaleManagedCoopCrossWorldAliasRuntimeGateway(
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType) {
        this(npcType, uuidType, markerType,
                HytaleManagedCoopCrossWorldAliasRuntimeGateway::world);
    }

    HytaleManagedCoopCrossWorldAliasRuntimeGateway(
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType,
            @Nonnull WorldLookup worlds) {
        this.npcType = npcType;
        this.uuidType = uuidType;
        this.markerType = markerType;
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    @Override
    public boolean execute(Location location, Runnable action) {
        World world = worlds.resolve(location.worldName());
        if (world == null || !world.isAlive()) {
            return false;
        }
        world.execute(action);
        return true;
    }

    @Nullable
    @Override
    public ProjectionObservation observe(Location location, UUID npcUuid) {
        ResolvedProjection resolved = resolve(location, npcUuid);
        if (resolved == null || resolved.npc().isDespawning()) {
            return null;
        }
        TameworkProjectionIdentityComponent marker = markerType != null
                ? resolved.store().getComponent(resolved.reference(), markerType) : null;
        return new ProjectionObservation(Observation.of(npcUuid, markerEvidence(marker)));
    }

    @Override
    public boolean markToDespawn(Location location, Observation observation) {
        ResolvedProjection resolved = resolve(location, observation.npcUuid());
        if (resolved == null || resolved.npc().isDespawning()) {
            return false;
        }
        TameworkProjectionIdentityComponent marker = markerType != null
                ? resolved.store().getComponent(resolved.reference(), markerType) : null;
        if (!Objects.equals(observation.marker(), markerEvidence(marker))) {
            return false;
        }
        resolved.npc().setToDespawn();
        return true;
    }

    @Nullable
    private ResolvedProjection resolve(Location location, UUID npcUuid) {
        if (npcType == null || uuidType == null) {
            return null;
        }
        World world = worlds.resolve(location.worldName());
        Store<EntityStore> store = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return null;
        }
        store.assertThread();
        if (!location.equals(LoadedNpcLocationResolver.resolve(store))) {
            return null;
        }
        Ref<EntityStore> reference = world.getEntityRef(npcUuid);
        if (reference == null || !reference.isValid()) {
            return null;
        }
        UUIDComponent identity = store.getComponent(reference, uuidType);
        NPCEntity npc = store.getComponent(reference, npcType);
        return identity != null && npcUuid.equals(identity.getUuid()) && npc != null
                ? new ResolvedProjection(store, reference, npc) : null;
    }

    @Nullable
    private static World world(String worldName) {
        Universe universe = Universe.get();
        return universe != null ? universe.getWorld(worldName) : null;
    }

    @Nullable
    private static MarkerEvidence markerEvidence(
            @Nullable TameworkProjectionIdentityComponent marker) {
        return marker == null ? null : new MarkerEvidence(
                marker.getProfileId(), marker.getOperationId(), marker.getProjectionKind(),
                marker.getSlotKey(), marker.getSourceNpcUuid(), marker.getGeneration());
    }

    private record ResolvedProjection(Store<EntityStore> store,
                                      Ref<EntityStore> reference,
                                      NPCEntity npc) {
    }

    @FunctionalInterface
    interface WorldLookup {
        @Nullable
        World resolve(@Nonnull String worldName);
    }
}
