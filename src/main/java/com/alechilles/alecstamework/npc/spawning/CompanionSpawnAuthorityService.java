package com.alechilles.alecstamework.npc.spawning;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.reference.InvalidatablePersistentRef;
import com.hypixel.hytale.server.core.modules.entity.component.WorldGenId;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.StoredFlock;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.spawning.beacons.LegacySpawnBeaconEntity;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Releases tamed NPCs from the base-game spawn authorities that created them.
 *
 * <p>Spawn markers keep a reverse list in addition to the reference stored on
 * each NPC. Both sides must be cleared or marker deactivation can store and
 * later restore a companion after it has already been tamed.</p>
 */
public final class CompanionSpawnAuthorityService {
    private CompanionSpawnAuthorityService() {
    }

    /** Detaches one live companion from marker, beacon, and NPC spawn state. */
    public static boolean detach(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        Types types = Types.resolve();
        UUID npcUuid = componentUuid(npcRef, store, types.uuid());
        boolean changed = detachMarker(
                npcRef, npcUuid, store, types.markerReference(),
                types.markerEntity()
        );
        changed |= detachBeacon(
                npcRef, store, types.beaconReference(), types.beaconEntity()
        );
        changed |= disableNpcSpawnState(npcRef, store, types.npc());
        return changed;
    }

    /**
     * Repairs reverse marker references for tamed NPCs live with the marker.
     * Unloaded members keep both reference sides until their NPC loads.
     */
    public static int detachLoadedTamedMembers(
            Ref<EntityStore> markerRef,
            Store<EntityStore> store,
            ComponentType<EntityStore, SpawnMarkerEntity> markerType,
            ComponentType<EntityStore, TameworkTamedComponent> tamedType
    ) {
        if (markerRef == null || !markerRef.isValid() || store == null
                || markerType == null || tamedType == null) {
            return 0;
        }
        SpawnMarkerEntity marker = store.getComponent(markerRef, markerType);
        if (marker == null) {
            return 0;
        }
        InvalidatablePersistentRef[] references = marker.getNpcReferences();
        if (references == null || references.length == 0) {
            return 0;
        }
        List<MarkerMember> tamedMembers = new ArrayList<>();
        for (InvalidatablePersistentRef member : references.clone()) {
            if (member == null) {
                continue;
            }
            UUID memberUuid = member.getUuid();
            Ref<EntityStore> npcRef = member.getEntity(store);
            if (npcRef == null || !npcRef.isValid()) {
                continue;
            }
            TameworkTamedComponent tamed = store.getComponent(
                    npcRef, tamedType
            );
            if (tamed == null || !tamed.isTamed()) {
                continue;
            }
            tamedMembers.add(new MarkerMember(npcRef, memberUuid));
        }
        int detached = 0;
        for (MarkerMember member : tamedMembers) {
            boolean changed = member.reference() != null
                    && member.reference().isValid()
                    && detach(member.reference(), store);
            if (containsMarkerMember(marker, member.uuid())) {
                removeMarkerMember(marker, member.uuid());
                markMarkerDirty(markerRef, store);
                changed = true;
            }
            if (changed) {
                detached++;
            }
        }
        if (!tamedMembers.isEmpty()) {
            prepareRespawnIfEmpty(marker, store);
        }
        return detached;
    }

    static int removeMarkerMember(SpawnMarkerEntity marker, UUID npcUuid) {
        if (marker == null || npcUuid == null) {
            return 0;
        }
        InvalidatablePersistentRef[] references = marker.getNpcReferences();
        if (references == null || references.length == 0) {
            return 0;
        }
        int remaining = 0;
        for (InvalidatablePersistentRef reference : references) {
            if (!matches(reference, npcUuid)) {
                remaining++;
            }
        }
        int removed = references.length - remaining;
        if (removed == 0) {
            return 0;
        }
        InvalidatablePersistentRef[] retained =
                new InvalidatablePersistentRef[remaining];
        int index = 0;
        for (InvalidatablePersistentRef reference : references) {
            if (!matches(reference, npcUuid)) {
                retained[index++] = reference;
            }
        }
        marker.setNpcReferences(retained);
        marker.setSpawnCount(remaining);
        return removed;
    }

    static boolean detachMarker(
            Ref<EntityStore> npcRef,
            @Nullable UUID npcUuid,
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, SpawnMarkerReference>
                    referenceType,
            @Nullable ComponentType<EntityStore, SpawnMarkerEntity> markerType
    ) {
        if (referenceType == null) {
            return false;
        }
        SpawnMarkerReference reference = store.getComponent(
                npcRef, referenceType
        );
        if (reference == null) {
            return false;
        }
        if (markerType != null && npcUuid != null) {
            Ref<EntityStore> markerRef = reference.getReference()
                    .getEntity(store);
            SpawnMarkerEntity marker = markerRef == null
                    || !markerRef.isValid()
                    ? null
                    : store.getComponent(markerRef, markerType);
            if (marker != null) {
                int removed = removeMarkerMember(marker, npcUuid);
                if (removed == 0) {
                    marker.setSpawnCount(Math.max(
                            0, marker.getSpawnCount() - 1
                    ));
                }
                prepareRespawnIfEmpty(marker, store);
                markMarkerDirty(markerRef, store);
            }
        }
        // A loaded tamed NPC must not remain under the lost-marker timeout.
        // If the marker is absent, its durable reverse UUID is left intact for
        // the marker-side load audit.
        store.removeComponent(npcRef, referenceType);
        return true;
    }

    private static boolean detachBeacon(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, SpawnBeaconReference>
                    referenceType,
            @Nullable ComponentType<EntityStore, LegacySpawnBeaconEntity>
                    beaconType
    ) {
        if (referenceType == null) {
            return false;
        }
        SpawnBeaconReference reference = store.getComponent(
                npcRef, referenceType
        );
        if (reference == null) {
            return false;
        }
        Ref<EntityStore> beaconRef = reference.getReference().getEntity(store);
        LegacySpawnBeaconEntity beacon = beaconRef == null || beaconType == null
                ? null
                : store.getComponent(beaconRef, beaconType);
        if (beacon != null && beacon.getSpawnController() != null) {
            var controller = beacon.getSpawnController();
            if (controller.getSpawnedEntities().contains(npcRef)) {
                controller.notifyNPCRemoval(npcRef, store);
            }
            // The 0.5.7 beacon LOAD path registers the beacon reference itself.
            // Remove that stale entry too so it cannot consume an NPC slot.
            if (beaconRef != npcRef
                    && controller.getSpawnedEntities().contains(beaconRef)) {
                controller.notifyNPCRemoval(beaconRef, store);
            }
        }
        store.tryRemoveComponent(npcRef, referenceType);
        return true;
    }

    private static boolean disableNpcSpawnState(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType
    ) {
        NPCEntity npc = npcType == null
                ? null
                : store.getComponent(npcRef, npcType);
        if (npc == null) {
            return false;
        }
        boolean changed = npc.updateSpawnTrackingState(false);
        if (npc.getSpawnConfiguration() != AssetMapWithIndexes.NOT_FOUND) {
            npc.setSpawnConfiguration(AssetMapWithIndexes.NOT_FOUND);
            changed = true;
        }
        if (npc.getEnvironment() != AssetMapWithIndexes.NOT_FOUND) {
            npc.setEnvironment(AssetMapWithIndexes.NOT_FOUND);
            changed = true;
        }
        if (npc.getSpawnRoleIndex() != AssetMapWithIndexes.NOT_FOUND) {
            npc.setSpawnRoleIndex(AssetMapWithIndexes.NOT_FOUND);
            changed = true;
        }
        return changed;
    }

    private static void prepareRespawnIfEmpty(
            SpawnMarkerEntity marker,
            Store<EntityStore> store
    ) {
        if (marker.getSpawnCount() > 0 || marker.getCachedMarker() == null
                || marker.getCachedMarker().isRealtimeRespawn()) {
            return;
        }
        try {
            Instant spawnAfter = store.getResource(
                    WorldTimeResource.getResourceType()
            ).getGameTime();
            Duration delay = marker.pollGameTimeRespawn();
            if (delay != null) {
                spawnAfter = spawnAfter.plus(delay);
            }
            marker.setSpawnAfter(spawnAfter);
        } catch (RuntimeException | LinkageError ignored) {
            // Marker initialization will retain its existing respawn state.
        }
        marker.setNpcReferences(null);
        StoredFlock storedFlock = marker.getStoredFlock();
        if (storedFlock != null) {
            storedFlock.clear();
        }
    }

    private static boolean containsMarkerMember(
            SpawnMarkerEntity marker,
            @Nullable UUID npcUuid
    ) {
        if (npcUuid == null) {
            return false;
        }
        InvalidatablePersistentRef[] references = marker.getNpcReferences();
        if (references == null) {
            return false;
        }
        for (InvalidatablePersistentRef reference : references) {
            if (matches(reference, npcUuid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(
            @Nullable InvalidatablePersistentRef reference,
            UUID npcUuid
    ) {
        return reference != null && npcUuid.equals(reference.getUuid());
    }

    private static void markMarkerDirty(
            Ref<EntityStore> markerRef,
            Store<EntityStore> store
    ) {
        ComponentType<EntityStore, WorldGenId> worldGenType =
                componentType(WorldGenId::getComponentType);
        if (worldGenType == null) {
            return;
        }
        WorldGenId existing = store.getComponent(markerRef, worldGenType);
        int worldGenId = existing == null
                ? WorldGenId.NON_WORLD_GEN_ID
                : existing.getWorldGenId();
        store.putComponent(
                markerRef, worldGenType, new WorldGenId(worldGenId)
        );
    }

    @Nullable
    private static UUID componentUuid(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType
    ) {
        UUIDComponent component = uuidType == null
                ? null
                : store.getComponent(reference, uuidType);
        return component == null ? null : component.getUuid();
    }

    @Nullable
    private static <T extends Component<EntityStore>>
    ComponentType<EntityStore, T> componentType(ComponentTypeSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ComponentTypeSupplier<T extends Component<EntityStore>> {
        ComponentType<EntityStore, T> get();
    }

    private record MarkerMember(
            Ref<EntityStore> reference,
            UUID uuid
    ) {
    }

    private record Types(
            ComponentType<EntityStore, UUIDComponent> uuid,
            ComponentType<EntityStore, NPCEntity> npc,
            ComponentType<EntityStore, SpawnMarkerReference> markerReference,
            ComponentType<EntityStore, SpawnBeaconReference> beaconReference,
            ComponentType<EntityStore, SpawnMarkerEntity> markerEntity,
            ComponentType<EntityStore, LegacySpawnBeaconEntity> beaconEntity
    ) {
        private static Types resolve() {
            return new Types(
                    componentType(UUIDComponent::getComponentType),
                    componentType(NPCEntity::getComponentType),
                    componentType(SpawnMarkerReference::getComponentType),
                    componentType(SpawnBeaconReference::getComponentType),
                    componentType(SpawnMarkerEntity::getComponentType),
                    componentType(LegacySpawnBeaconEntity::getComponentType)
            );
        }
    }
}
