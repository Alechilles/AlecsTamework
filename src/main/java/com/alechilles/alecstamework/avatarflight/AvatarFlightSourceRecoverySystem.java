package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Unparks source NPCs whose rider-side avatar-flight session no longer exists. */
public final class AvatarFlightSourceRecoverySystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType;
    private final ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, DeathComponent> deathType;
    private final AvatarFlightNpcParkingService parking = new AvatarFlightNpcParkingService();
    private final AvatarFlightMountLifecycleService lifecycle = new AvatarFlightMountLifecycleService();
    private final Query<EntityStore> query;

    public AvatarFlightSourceRecoverySystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType,
            @Nonnull ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType) {
        this.sourceType = sourceType;
        this.sessionType = sessionType;
        this.uuidType = uuidType;
        this.deathType = deathType;
        this.query = Query.and(sourceType, uuidType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> sourceRef = chunk.getReferenceTo(index);
        AvatarFlightSourceComponent source = chunk.getComponent(index, sourceType);
        UUIDComponent sourceUuid = chunk.getComponent(index, uuidType);
        if (sourceRef == null || source == null || sourceUuid == null || sourceUuid.getUuid() == null) return;
        Ref<EntityStore> riderRef = resolve(store, source.getRiderUuid());
        AvatarFlightMountSessionComponent session = riderRef == null || !riderRef.isValid()
                ? null : store.getComponent(riderRef, sessionType);
        boolean sourceDead = store.getComponent(sourceRef, deathType) != null;
        boolean paired = session != null
                && sourceUuid.getUuid().toString().equals(session.getSourceNpcUuid())
                && AvatarFlightRuntimeEpoch.isCurrent(source.getRuntimeEpoch())
                && AvatarFlightRuntimeEpoch.isCurrent(session.getRuntimeEpoch())
                && session.getPhase() != AvatarFlightMountPhase.RESTORING;
        if (sourceDead) {
            if (riderRef != null && riderRef.isValid()) {
                UUID riderUuid = parse(source.getRiderUuid());
                if (riderUuid != null) {
                    commandBuffer.run(bufferStore -> lifecycle.end(
                            bufferStore,
                            riderRef,
                            riderUuid,
                            AvatarFlightMountLifecycleService.EndReason.SOURCE_MISSING
                    ));
                }
            } else {
                commandBuffer.run(bufferStore -> {
                    if (sourceRef.isValid()) bufferStore.tryRemoveComponent(sourceRef, sourceType);
                });
            }
            return;
        }
        if (paired) return;
        recordStaleSourceOwner(store, sourceRef, source);
        commandBuffer.run(bufferStore -> {
            if (!sourceRef.isValid()) return;
            AvatarFlightSourceComponent current = bufferStore.getComponent(sourceRef, sourceType);
            if (current == null) return;
            parking.restore(bufferStore, sourceRef, current,
                    current.getOriginX(), current.getOriginY(), current.getOriginZ(), current.getOriginYaw());
            bufferStore.tryRemoveComponent(sourceRef, sourceType);
        });
    }

    private static void recordStaleSourceOwner(
            Store<EntityStore> store,
            Ref<EntityStore> sourceRef,
            AvatarFlightSourceComponent source
    ) {
        if (AvatarFlightRuntimeEpoch.isCurrent(source.getRuntimeEpoch())) {
            return;
        }
        var ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null
                ? null : store.getComponent(sourceRef, ownerType);
        if (owner != null) {
            AvatarFlightStaleOwnerRecoveryRegistry.record(owner.getOwnerId());
        }
    }

    @Nullable
    private static Ref<EntityStore> resolve(Store<EntityStore> store, String uuid) {
        UUID parsed = parse(uuid);
        return parsed == null ? null : store.getExternalData().getWorld().getEntityRef(parsed);
    }

    @Nullable
    private static UUID parse(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull @Override public Query<EntityStore> getQuery() { return query; }
}
