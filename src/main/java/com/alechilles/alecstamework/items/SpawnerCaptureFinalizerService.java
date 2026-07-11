package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerMutationContext;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies capture-side entity cleanup after a successful spawner capture.
 */
public final class SpawnerCaptureFinalizerService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    public void despawnNpc(Player player, Ref<EntityStore> targetRef, Entity targetEntity) {
        if (player == null) {
            return;
        }
        if (targetEntity instanceof NPCEntity npcEntity) {
            npcEntity.setToDespawn();
            return;
        }
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc != null) {
            npc.setToDespawn();
        }
    }

    public boolean finalizeCapture(Player player,
                                   ItemFeatureConfig config,
                                   Ref<EntityStore> targetRef,
                                   @Nullable CaptureCallbacks callbacks) {
        CaptureCallbacks safeCallbacks = callbacks == null ? CaptureCallbacks.NOOP : callbacks;
        if (player == null || config == null || targetRef == null || !targetRef.isValid()) {
            safeCallbacks.onDenied("capture-owner-target-unavailable");
            return false;
        }
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (store == null) {
            safeCallbacks.onDenied("capture-owner-store-unavailable");
            return false;
        }
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        UUID npcUuid = npc == null ? null : npc.getUuid();
        if (npc == null || npcUuid == null) {
            safeCallbacks.onDenied("capture-owner-npc-unavailable");
            return false;
        }
        OwnerMutationScheduler scheduler = resolveMutationScheduler();
        if (scheduler == null) {
            safeCallbacks.onDenied("owner-mutation-scheduler-unavailable");
            return false;
        }
        TameworkOwnerComponent currentOwner = readOwner(targetRef, store);
        UUID retainedOwnerId = config.isCaptureClearsOwner() || currentOwner == null
                ? null
                : currentOwner.getOwnerId();
        String retainedOwnerName = config.isCaptureClearsOwner() || currentOwner == null
                ? null
                : currentOwner.getOwnerName();
        OwnerPopulationOperation operation = config.isCaptureClearsOwner()
                ? OwnerPopulationOperation.OWNER_CLEAR
                : OwnerPopulationOperation.LIFECYCLE_CHANGE;
        return scheduler.schedule(
                targetRef,
                store,
                retainedOwnerId,
                retainedOwnerName,
                CompanionLifecycleState.CAPTURED,
                operation,
                false,
                "spawner-capture:" + npcUuid + ":clear-owner=" + config.isCaptureClearsOwner(),
                new OwnerMutationScheduler.MutationCallbacks() {
                    @Override
                    public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                        safeCallbacks.onDenied(reason);
                    }

                    @Override
                    public boolean beforeApply(@Nonnull String profileId) {
                        return safeCallbacks.beforeApply(profileId);
                    }

                    @Override
                    public void onApplyCompensated(@Nonnull String profileId, @Nonnull String reason) {
                        safeCallbacks.onApplyCompensated(profileId, reason);
                    }

                    @Override
                    public void onApplied(@Nonnull OwnerPopulationDecision decision,
                                          @Nonnull String profileId,
                                          @Nonnull OwnerMutationContext context) {
                        NPCEntity liveNpc = context.store().getComponent(
                                context.npcRef(), NPCEntity.getComponentType()
                        );
                        try {
                            safeCallbacks.onApplied(profileId, context);
                        } finally {
                            if (config.isCaptureClearsOwner()
                                    && liveNpc != null
                                    && liveNpc.getRole() != null
                                    && liveNpc.getRole().getMarkedEntitySupport() != null) {
                                liveNpc.getRole().getMarkedEntitySupport()
                                        .setMarkedEntity(MASTER_TARGET_SLOT, null);
                            }
                            despawnNpc(player, context.npcRef(), liveNpc);
                        }
                    }

                    @Override
                    public void onDurabilityDegraded(@Nonnull String reason) {
                        safeCallbacks.onDurabilityDegraded(reason);
                    }
                }
        );
    }

    @Nullable
    private static OwnerMutationScheduler resolveMutationScheduler() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerMutationScheduler();
    }

    @Nullable
    private static TameworkOwnerComponent readOwner(@Nonnull Ref<EntityStore> targetRef,
                                                    @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        return type == null ? null : store.getComponent(targetRef, type);
    }

    public interface CaptureCallbacks {
        CaptureCallbacks NOOP = new CaptureCallbacks() {
        };

        default boolean beforeApply(@Nonnull String profileId) {
            return true;
        }

        default void onApplyCompensated(@Nonnull String profileId, @Nonnull String reason) {
        }

        default void onApplied(@Nonnull String profileId) {
        }

        default void onApplied(@Nonnull String profileId, @Nonnull OwnerMutationContext context) {
            onApplied(profileId);
        }

        default void onDenied(@Nonnull String reason) {
        }

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }
}

