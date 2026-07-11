package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.RecoveryProjectionReconciliationService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
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
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Captures immutable identity from newly-added recovery projections and requests reconciliation.
 *
 * <p>No live reference, store, NPC, or component object crosses the asynchronous service boundary.
 * This system performs no ECS mutation; every component read goes through the callback's command
 * buffer.</p>
 */
public final class RecoveryProjectionReconciliationSystem extends RefSystem<EntityStore> {
    private final RecoveryProjectionReconciliationService reconciliationService;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType;
    @Nullable
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksType;

    public RecoveryProjectionReconciliationSystem(
            @Nonnull RecoveryProjectionReconciliationService reconciliationService,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nullable ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksType) {
        this.reconciliationService = Objects.requireNonNull(
                reconciliationService, "reconciliationService");
        this.npcType = npcType;
        this.uuidType = uuidType;
        this.projectionType = projectionType;
        this.commandLinksType = commandLinksType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || uuidType == null || projectionType == null) {
            return;
        }
        TameworkProjectionIdentityComponent marker =
                commandBuffer.getComponent(reference, projectionType);
        if (marker == null || !TameworkProjectionIdentityComponent.KIND_RECOVERY.equals(
                marker.getProjectionKind())) {
            return;
        }
        NPCEntity npc = commandBuffer.getComponent(reference, npcType);
        UUIDComponent uuid = commandBuffer.getComponent(reference, uuidType);
        if (npc == null || uuid == null) {
            return;
        }
        TameworkCommandLinksComponent commandLinks = commandLinksType != null
                ? commandBuffer.getComponent(reference, commandLinksType)
                : null;
        List<String> toolIds = commandLinks == null || commandLinks.getToolIds() == null
                ? List.of()
                : Arrays.asList(commandLinks.getToolIds().clone());
        RecoveryProjectionReconciliationService.Observation observation =
                RecoveryProjectionReconciliationService.Observation.fromToolIds(
                        marker.getProjectionKind(),
                        marker.getProfileId(),
                        marker.getOperationId(),
                        marker.getSourceNpcUuid(),
                        marker.getGeneration(),
                        uuid.getUuid(),
                        npc.getUuid(),
                        commandLinksType != null,
                        toolIds
                );
        reconciliationService.reconcile(observation);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Recovery reconciliation is driven only by an entity becoming visible.
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null || uuidType == null || projectionType == null) {
            return Query.any();
        }
        return Query.and(npcType, uuidType, projectionType);
    }
}
