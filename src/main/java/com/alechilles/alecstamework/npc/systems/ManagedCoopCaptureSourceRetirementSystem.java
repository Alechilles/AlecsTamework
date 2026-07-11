package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RemovalObservation;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Copies stable identity from removed capture sources and requests durable capture completion.
 *
 * <p>The callback reads exclusively through its command buffer. Only immutable UUID and marker
 * values leave the callback; no reference, store, component, or NPC is retained.</p>
 */
public final class ManagedCoopCaptureSourceRetirementSystem extends RefSystem<EntityStore> {
    private final ManagedCoopCaptureSourceRetirementService retirementService;
    @Nullable
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    @Nullable
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType;

    public ManagedCoopCaptureSourceRetirementSystem(
            @Nonnull ManagedCoopCaptureSourceRetirementService retirementService,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType) {
        this.retirementService = Objects.requireNonNull(
                retirementService, "retirementService");
        this.uuidType = uuidType;
        this.markerType = markerType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Source retirement is confirmed only after removal.
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (uuidType == null || markerType == null) {
            return;
        }
        UUIDComponent uuidComponent = commandBuffer.getComponent(reference, uuidType);
        TameworkProjectionIdentityComponent marker =
                commandBuffer.getComponent(reference, markerType);
        UUID removedUuid = uuidComponent != null ? uuidComponent.getUuid() : null;
        if (removedUuid == null || marker == null) {
            return;
        }
        RemovalObservation observation = new RemovalObservation(
                removedUuid,
                marker.getProfileId(),
                marker.getOperationId(),
                marker.getProjectionKind(),
                marker.getSlotKey(),
                marker.getSourceNpcUuid(),
                marker.getGeneration()
        );
        retirementService.confirmRemoved(observation);
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (uuidType == null || markerType == null) {
            return Query.any();
        }
        return Query.and(uuidType, markerType);
    }
}
