package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.NeedsResourceConsumeAttemptTracker;
import com.alechilles.alecstamework.npc.progression.NeedsResourceMovementDiagnostics;
import com.alechilles.alecstamework.npc.progression.NeedsResourceMovementProgressTracker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Logs gated movement-phase diagnostics for needs seek resource movement.
 */
public final class ActionTameworkNeedsResourceMovementDiagnostic extends TameworkActionBase {
    @Nullable
    private final String resourceType;
    @Nullable
    private final String stage;
    @Nullable
    private final String detail;

    public ActionTameworkNeedsResourceMovementDiagnostic(BuilderActionTameworkNeedsResourceMovementDiagnostic builder,
                                                         BuilderSupport support) {
        super(builder);
        this.resourceType = builder.getResourceType(support);
        this.stage = builder.getStage(support);
        this.detail = builder.getDetail(support);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && store != null && npcRef.isValid();
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        Vector3d target = resolveTarget(infoProvider);
        Vector3d currentPosition = resolveCurrentPosition(npcRef, store);
        if ("move_start".equals(stage)) {
            NeedsResourceConsumeAttemptTracker.clear(npcUuid, resourceType);
            NeedsResourceMovementProgressTracker.recordStart(
                    npcUuid,
                    resourceType,
                    target,
                    currentPosition,
                    System.currentTimeMillis()
            );
        }
        NeedsResourceMovementProgressTracker.ProgressSnapshot progress =
                NeedsResourceMovementProgressTracker.snapshot(
                        npcUuid,
                        resourceType,
                        currentPosition,
                        System.currentTimeMillis()
                );
        NeedsResourceMovementDiagnostics.maybeLog(
                NeedsResourceMovementDiagnostics.resolveNpcId(npcRef, store),
                CompanionRoleIdResolver.resolveRoleId(npcRef, store),
                resourceType,
                stage,
                detail,
                target,
                currentPosition,
                progress
        );
        clearProgressAfterTerminalStage(npcUuid);
        return true;
    }

    private void clearProgressAfterTerminalStage(UUID npcUuid) {
        if (clearsMovementProgress(stage)) {
            NeedsResourceMovementProgressTracker.clear(npcUuid, resourceType);
            NeedsResourceConsumeAttemptTracker.clear(npcUuid, resourceType);
        }
    }

    private static boolean clearsMovementProgress(@Nullable String stage) {
        return "consume_delay_start".equals(stage)
                || "target_lost".equals(stage)
                || "nav_defer".equals(stage)
                || "nav_blocked".equals(stage)
                || "move_stalled".equals(stage)
                || "move_timeout".equals(stage)
                || "consume_delay_timeout".equals(stage)
                || "consume_attempt".equals(stage);
    }

    @Nullable
    private static UUID resolveNpcUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    @Nullable
    private static Vector3d resolveCurrentPosition(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        return transform == null ? null : transform.getPosition();
    }

    @Nullable
    private static Vector3d resolveTarget(@Nullable InfoProvider infoProvider) {
        if (infoProvider == null || !infoProvider.hasPosition()) {
            return null;
        }
        IPositionProvider provider = infoProvider.getPositionProvider();
        if (provider == null || !provider.hasPosition()) {
            return null;
        }
        double x = provider.getX();
        double y = provider.getY();
        double z = provider.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        return new Vector3d(x, y, z);
    }
}
