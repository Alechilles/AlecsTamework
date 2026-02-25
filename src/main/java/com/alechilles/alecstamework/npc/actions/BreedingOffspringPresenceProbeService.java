package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.CompanionModelScaleService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Schedules and logs delayed checks that verify spawned offspring are still present in-world.
 */
final class BreedingOffspringPresenceProbeService {
    private static final long[] PRESENCE_CHECK_DELAYS_MS = new long[] { 900L, 3000L, 8000L };

    void schedulePresenceChecks(@Nullable World world,
                                @Nullable UUID childUuid,
                                @Nullable String childRoleId,
                                @Nullable UUID parentAUuid,
                                @Nullable UUID parentBUuid) {
        if (world == null
                || childUuid == null
                || childRoleId == null
                || childRoleId.isBlank()
                || parentAUuid == null
                || parentBUuid == null) {
            return;
        }
        for (long delayMs : PRESENCE_CHECK_DELAYS_MS) {
            long safeDelayMs = Math.max(0L, delayMs);
            scheduleWorldAction(
                    world,
                    safeDelayMs,
                    "offspring-presence-check-" + safeDelayMs + "ms",
                    () -> verifyOffspringPresence(world, childUuid, childRoleId, parentAUuid, parentBUuid, safeDelayMs)
            );
        }
    }

    private void verifyOffspringPresence(@Nonnull World world,
                                         @Nonnull UUID childUuid,
                                         @Nonnull String childRoleId,
                                         @Nonnull UUID parentAUuid,
                                         @Nonnull UUID parentBUuid,
                                         long checkDelayMs) {
        Ref<EntityStore> childRef = world.getEntityRef(childUuid);
        if (childRef == null || !childRef.isValid()) {
            logWarn(String.format(
                    "Breeding offspring missing after %dms: child=%s role=%s parentA=%s parentB=%s.",
                    checkDelayMs,
                    childUuid,
                    childRoleId,
                    parentAUuid,
                    parentBUuid
            ));
            return;
        }
        Store<EntityStore> store = world.getEntityStore() != null
                ? world.getEntityStore().getStore()
                : null;
        if (store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(childRef, NPCEntity.getComponentType());
        if (npc == null) {
            logWarn(String.format(
                    "Breeding offspring reference present but NPC component missing after %dms: child=%s role=%s parentA=%s parentB=%s.",
                    checkDelayMs,
                    childUuid,
                    childRoleId,
                    parentAUuid,
                    parentBUuid
            ));
            return;
        }
        TransformComponent transform = store.getComponent(childRef, TransformComponent.getComponentType());
        Vector3d position = transform != null ? transform.getPosition() : null;
        double scale = CompanionModelScaleService.resolveCurrentScale(childRef, store, 1.0);
        String stage = resolveLifeStage(childRef, store);
        logInfo(String.format(
                "Breeding offspring confirmed in-world after %dms: child=%s role=%s pos=%s scale=%.2f stage=%s.",
                checkDelayMs,
                childUuid,
                childRoleId,
                position != null
                        ? String.format("(%.2f, %.2f, %.2f)", position.x, position.y, position.z)
                        : "(unknown)",
                scale,
                stage != null ? stage : "unknown"
        ));
    }

    @Nullable
    private static String resolveLifeStage(@Nonnull Ref<EntityStore> childRef, @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkLifeStageComponent> stageType = TameworkLifeStageComponent.getComponentType();
        if (stageType == null) {
            return null;
        }
        TameworkLifeStageComponent stageComponent = store.getComponent(childRef, stageType);
        return stageComponent != null ? stageComponent.getStage() : null;
    }

    private void scheduleWorldAction(@Nonnull World world, long delayMs, @Nonnull String actionLabel, @Nonnull Runnable action) {
        long safeDelayMs = Math.max(0L, delayMs);
        CompletableFuture.runAsync(() -> executeWorldAction(world, actionLabel, action),
                CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS))
                .exceptionally(ex -> {
                    logWarn("Breeding delayed action failed asynchronously: " + actionLabel + ".", ex);
                    return null;
                });
    }

    private void executeWorldAction(@Nonnull World world, @Nonnull String actionLabel, @Nonnull Runnable action) {
        try {
            world.execute(() -> {
                try {
                    action.run();
                } catch (Throwable ex) {
                    logWarn("Breeding delayed action failed during world execution: " + actionLabel + ".", ex);
                }
            });
        } catch (Throwable ex) {
            logWarn("Breeding delayed action failed before world execution: " + actionLabel + ".", ex);
        }
    }

    private void logWarn(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null || message == null || message.isBlank()) {
            return;
        }
        instance.getLogger().at(Level.WARNING).log(message);
    }

    private void logWarn(String message, Throwable error) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null || message == null || message.isBlank()) {
            return;
        }
        if (error == null) {
            instance.getLogger().at(Level.WARNING).log(message);
            return;
        }
        instance.getLogger().at(Level.WARNING).withCause(error).log(message);
    }

    private void logInfo(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null || message == null || message.isBlank()) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(message);
    }
}
