package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.reference.InvalidatablePersistentRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Emits focused diagnostics for rat NPC lifecycle removals while debug logging is enabled.
 */
public final class CompanionDespawnDiagnosticsSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, SpawnMarkerReference> spawnMarkerReferenceType;
    private final ComponentType<EntityStore, SpawnBeaconReference> spawnBeaconReferenceType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final Map<String, TrackedNpcSnapshot> trackedByReference = new HashMap<>();

    public CompanionDespawnDiagnosticsSystem(ComponentType<EntityStore, NPCEntity> npcType,
                                             ComponentType<EntityStore, TameworkTamedComponent> tamedType,
                                             ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
                                             ComponentType<EntityStore, SpawnMarkerReference> spawnMarkerReferenceType,
                                             ComponentType<EntityStore, SpawnBeaconReference> spawnBeaconReferenceType,
                                             ComponentType<EntityStore, UUIDComponent> uuidType) {
        this.npcType = npcType;
        this.tamedType = tamedType;
        this.ownerType = ownerType;
        this.spawnMarkerReferenceType = spawnMarkerReferenceType;
        this.spawnBeaconReferenceType = spawnBeaconReferenceType;
        this.uuidType = uuidType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        String refKey = key(reference);
        if (!isDebugEnabled()) {
            trackedByReference.remove(refKey);
            return;
        }
        if (npcType == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, npcType);
        if (npc == null) {
            return;
        }
        String roleName = npc.getRoleName();
        if (!isRatRole(roleName)) {
            trackedByReference.remove(refKey);
            return;
        }
        TameworkTamedComponent tamed = tamedType == null ? null : store.getComponent(reference, tamedType);
        TameworkOwnerComponent owner = ownerType == null ? null : store.getComponent(reference, ownerType);
        SpawnMarkerReference markerReference = spawnMarkerReferenceType == null
                ? null
                : store.getComponent(reference, spawnMarkerReferenceType);
        SpawnBeaconReference beaconReference = spawnBeaconReferenceType == null
                ? null
                : store.getComponent(reference, spawnBeaconReferenceType);
        UUID markerEntityUuid = resolveReferencedEntityUuid(markerReference == null ? null : markerReference.getReference(), store);
        UUID beaconEntityUuid = resolveReferencedEntityUuid(beaconReference == null ? null : beaconReference.getReference(), store);
        TrackedNpcSnapshot snapshot = new TrackedNpcSnapshot(
                System.currentTimeMillis(),
                npc.getUuid(),
                roleName,
                npc.getSpawnConfiguration(),
                tamed != null && tamed.isTamed(),
                owner != null ? owner.getOwnerId() : null,
                owner != null ? owner.getOwnerName() : null,
                String.valueOf(reason),
                markerReference != null,
                beaconReference != null,
                markerEntityUuid,
                beaconEntityUuid
        );
        trackedByReference.put(refKey, snapshot);
        log(
                Level.INFO,
                String.format(
                        "Despawn diagnostics: track add ref=%s uuid=%s role=%s tamed=%s spawnConfig=%d addReason=%s ownerId=%s ownerName=%s markerRef=%s markerEntityUuid=%s beaconRef=%s beaconEntityUuid=%s",
                        refKey,
                        uuidText(snapshot.uuid),
                        safe(snapshot.roleName),
                        snapshot.tamed,
                        snapshot.spawnConfiguration,
                        safe(snapshot.addReason),
                        uuidText(snapshot.ownerId),
                        safe(snapshot.ownerName),
                        snapshot.hadMarkerReference,
                        uuidText(snapshot.markerEntityUuid),
                        snapshot.hadBeaconReference,
                        uuidText(snapshot.beaconEntityUuid)
                )
        );
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        String refKey = key(reference);
        TrackedNpcSnapshot tracked = trackedByReference.remove(refKey);
        if (!isDebugEnabled()) {
            return;
        }
        NPCEntity npc = npcType == null ? null : store.getComponent(reference, npcType);
        String roleName = resolveRoleName(npc, tracked);
        if (!isRatRole(roleName)) {
            return;
        }
        TameworkTamedComponent tamed = tamedType == null ? null : store.getComponent(reference, tamedType);
        TameworkOwnerComponent owner = ownerType == null ? null : store.getComponent(reference, ownerType);
        SpawnMarkerReference markerReference = spawnMarkerReferenceType == null
                ? null
                : store.getComponent(reference, spawnMarkerReferenceType);
        SpawnBeaconReference beaconReference = spawnBeaconReferenceType == null
                ? null
                : store.getComponent(reference, spawnBeaconReferenceType);
        boolean isTamed = tamed != null ? tamed.isTamed() : tracked != null && tracked.tamed;
        UUID ownerId = owner != null ? owner.getOwnerId() : tracked != null ? tracked.ownerId : null;
        String ownerName = owner != null ? owner.getOwnerName() : tracked != null ? tracked.ownerName : null;
        boolean hasMarkerReference = markerReference != null || tracked != null && tracked.hadMarkerReference;
        boolean hasBeaconReference = beaconReference != null || tracked != null && tracked.hadBeaconReference;
        UUID markerEntityUuid = markerReference != null
                ? resolveReferencedEntityUuid(markerReference.getReference(), store)
                : tracked != null ? tracked.markerEntityUuid : null;
        UUID beaconEntityUuid = beaconReference != null
                ? resolveReferencedEntityUuid(beaconReference.getReference(), store)
                : tracked != null ? tracked.beaconEntityUuid : null;
        int spawnConfiguration = npc != null
                ? npc.getSpawnConfiguration()
                : tracked != null ? tracked.spawnConfiguration : Integer.MAX_VALUE;
        long aliveMs = tracked == null ? -1L : (System.currentTimeMillis() - tracked.addedAtMs);
        log(
                Level.WARNING,
                String.format(
                        "Despawn diagnostics: rat removed ref=%s uuid=%s role=%s tamed=%s spawnConfig=%s removeReason=%s aliveMs=%s addReason=%s trackedRole=%s trackedSpawnConfig=%s ownerId=%s ownerName=%s markerRef=%s markerEntityUuid=%s beaconRef=%s beaconEntityUuid=%s",
                        refKey,
                        uuidText(npc != null ? npc.getUuid() : tracked != null ? tracked.uuid : null),
                        safe(roleName),
                        isTamed,
                        spawnConfiguration == Integer.MAX_VALUE ? "<unknown>" : Integer.toString(spawnConfiguration),
                        safe(String.valueOf(reason)),
                        aliveMs < 0 ? "<unknown>" : Long.toString(aliveMs),
                        tracked != null ? safe(tracked.addReason) : "<unknown>",
                        tracked != null ? safe(tracked.roleName) : "<unknown>",
                        tracked != null ? Integer.toString(tracked.spawnConfiguration) : "<unknown>",
                        uuidText(ownerId),
                        safe(ownerName),
                        hasMarkerReference,
                        uuidText(markerEntityUuid),
                        hasBeaconReference,
                        uuidText(beaconEntityUuid)
                )
        );
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null) {
            return Query.any();
        }
        return Query.and(npcType);
    }

    private static boolean isRatRole(@Nullable String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        String normalized = roleName.trim().toLowerCase();
        return "rat".equals(normalized) || normalized.endsWith("_rat");
    }

    @Nullable
    private static String resolveRoleName(@Nullable NPCEntity npc, @Nullable TrackedNpcSnapshot tracked) {
        if (npc != null && npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        return tracked != null ? tracked.roleName : null;
    }

    private static String key(@Nonnull Ref<EntityStore> reference) {
        return reference.toString();
    }

    private static String safe(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private static String uuidText(@Nullable UUID uuid) {
        return uuid == null ? "<none>" : uuid.toString();
    }

    @Nullable
    private UUID resolveReferencedEntityUuid(@Nullable InvalidatablePersistentRef persistentRef, @Nonnull Store<EntityStore> store) {
        if (persistentRef == null || uuidType == null) {
            return null;
        }
        Ref<EntityStore> entityRef = persistentRef.getEntity(store);
        if (entityRef == null || !entityRef.isValid()) {
            return null;
        }
        UUIDComponent uuidComponent = store.getComponent(entityRef, uuidType);
        return uuidComponent == null ? null : uuidComponent.getUuid();
    }

    private static boolean isDebugEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugDespawnEnabled();
    }

    private static void log(Level level, String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null || message == null || message.isBlank()) {
            return;
        }
        instance.getLogger().at(level).log(message);
    }

    private static final class TrackedNpcSnapshot {
        private final long addedAtMs;
        private final UUID uuid;
        private final String roleName;
        private final int spawnConfiguration;
        private final boolean tamed;
        private final UUID ownerId;
        private final String ownerName;
        private final String addReason;
        private final boolean hadMarkerReference;
        private final boolean hadBeaconReference;
        private final UUID markerEntityUuid;
        private final UUID beaconEntityUuid;

        private TrackedNpcSnapshot(long addedAtMs,
                                   UUID uuid,
                                   String roleName,
                                   int spawnConfiguration,
                                   boolean tamed,
                                   UUID ownerId,
                                   String ownerName,
                                   String addReason,
                                   boolean hadMarkerReference,
                                   boolean hadBeaconReference,
                                   UUID markerEntityUuid,
                                   UUID beaconEntityUuid) {
            this.addedAtMs = addedAtMs;
            this.uuid = uuid;
            this.roleName = roleName;
            this.spawnConfiguration = spawnConfiguration;
            this.tamed = tamed;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.addReason = addReason;
            this.hadMarkerReference = hadMarkerReference;
            this.hadBeaconReference = hadBeaconReference;
            this.markerEntityUuid = markerEntityUuid;
            this.beaconEntityUuid = beaconEntityUuid;
        }
    }
}
