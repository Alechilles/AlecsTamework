package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedsResourceTarget;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Releases a previously reserved needs-resource approach target after successful seek completion.
 */
public final class ActionTameworkNeedsResourceReleaseTarget extends TameworkActionBase {
    @Nullable
    private final String resourceType;

    public ActionTameworkNeedsResourceReleaseTarget(BuilderActionTameworkNeedsResourceReleaseTarget builder,
                                                   BuilderSupport support) {
        super(builder);
        this.resourceType = builder.getResourceType(support);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && store != null && npcRef.isValid() && resolveTarget(infoProvider) != null;
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        Vector3d target = resolveTarget(infoProvider);
        if (target == null || npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        SensorTameworkNeedsResourceTarget.releaseTarget(npcRef, store, resourceType, target);
        return true;
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
