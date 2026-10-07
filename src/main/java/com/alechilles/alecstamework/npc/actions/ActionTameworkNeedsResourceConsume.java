package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.NeedsResourceConsumeAttemptTracker;
import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedsResourceTarget;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Action that performs explicit needs resource consumption driven by template flow.
 */
public final class ActionTameworkNeedsResourceConsume extends TameworkActionBase {
    // Just longer than the 10 s shared search snapshot, so a stale entry cannot hand the source
    // back, and short enough that a refilled trough is used again quickly.
    private static final double FAILED_CONSUME_SUPPRESS_SECONDS = 12.0;

    @Nullable
    private final String resourceType;
    @Nullable
    private final String[] foodItemIds;
    private final boolean releaseTarget;

    public ActionTameworkNeedsResourceConsume(BuilderActionTameworkNeedsResourceConsume builder, BuilderSupport support) {
        super(builder);
        this.resourceType = builder.getResourceType(support);
        this.foodItemIds = builder.getFoodItemIds(support);
        this.releaseTarget = builder.getReleaseTarget(support);
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
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        Vector3d consumeOrigin = resolveConsumeOrigin(infoProvider);
        CompanionNeedsService.logResourceConsumeActionReached(
                npcRef,
                store,
                roleId,
                resourceType,
                consumeOrigin
        );
        boolean consumed = CompanionNeedsService.applyResourceConsumeWithDiagnostics(
                npcRef,
                store,
                roleId,
                resourceType,
                foodItemIds,
                consumeOrigin
        );
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        NeedsResourceConsumeAttemptTracker.record(npcUuid, resourceType, consumed, System.currentTimeMillis());
        if (!consumed && consumeOrigin != null) {
            // The source ran dry or changed after the search saw it. Without a rejection the cached
            // search result and the recent water target hand this NPC the same empty source again.
            SensorTameworkNeedsResourceTarget.rejectTarget(
                    npcUuid, resourceType, consumeOrigin, FAILED_CONSUME_SUPPRESS_SECONDS);
        }
        if (releaseTarget) {
            SensorTameworkNeedsResourceTarget.releaseTarget(npcRef, store, resourceType, consumeOrigin);
        }
        return consumed;
    }

    @Nullable
    private static UUID resolveNpcUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    @Nullable
    private static Vector3d resolveConsumeOrigin(@Nullable InfoProvider infoProvider) {
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
