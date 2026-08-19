package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedsResourceTarget;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Records a failed needs seek target so the next resource scan can skip it temporarily.
 */
public final class ActionTameworkNeedsResourceRejectTarget extends TameworkActionBase {
    @Nullable
    private final String resourceType;
    private final double suppressSeconds;

    public ActionTameworkNeedsResourceRejectTarget(BuilderActionTameworkNeedsResourceRejectTarget builder,
                                                   BuilderSupport support) {
        super(builder);
        this.resourceType = builder.getResourceType(support);
        this.suppressSeconds = builder.getSuppressSeconds(support);
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
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        boolean rejected = SensorTameworkNeedsResourceTarget.rejectTarget(
                npcUuid,
                resolveWorldName(store),
                resourceType,
                target,
                suppressSeconds
        );
        SensorTameworkNeedsResourceTarget.releaseTarget(npcRef, store, resourceType, target);
        return rejected;
    }

    @Nullable
    private static String resolveWorldName(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        String worldName = world.getName();
        return worldName == null || worldName.isBlank() ? null : worldName;
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
