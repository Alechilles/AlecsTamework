package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkAttitudeFromTargetSlot;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import java.util.EnumSet;
import javax.annotation.Nonnull;

/**
 * Entity filter that checks a candidate NPC's attitude toward a marked target slot.
 */
public final class EntityFilterTameworkAttitudeFromTargetSlot extends TameworkEntityFilterBase {
    public static final String TYPE = "TameworkAttitudeFromTargetSlot";

    private final int sourceTargetSlot;
    private final EnumSet<Attitude> attitudes;
    private final boolean useSelfWhenSourceMissing;

    public EntityFilterTameworkAttitudeFromTargetSlot(
            @Nonnull BuilderEntityFilterTameworkAttitudeFromTargetSlot builder,
            @Nonnull BuilderSupport support) {
        this.sourceTargetSlot = builder.getSourceTargetSlot(support);
        this.attitudes = builder.getAttitudes(support);
        this.useSelfWhenSourceMissing = builder.useSelfWhenSourceMissing(support);
    }

    @Override
    public boolean matchesEntity(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Ref<EntityStore> targetRef,
                                 @Nonnull Role role,
                                 @Nonnull Store<EntityStore> store) {
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }
        Ref<EntityStore> sourceRef = resolveSourceRef(ref, role, store);
        if (sourceRef == null || !sourceRef.isValid()) {
            return false;
        }
        NPCEntity targetNpc = store.getComponent(targetRef, NPCEntity.getComponentType());
        Role targetRole = targetNpc != null ? targetNpc.getRole() : null;
        WorldSupport worldSupport = NpcSupportAccess.world(targetRole, targetRef, store);
        if (worldSupport == null) {
            return false;
        }
        worldSupport.requireAttitudeCache();
        Attitude attitude = worldSupport.getAttitude(targetRef, sourceRef, store);
        return attitude != null && attitudes.contains(attitude);
    }

    @Override
    public int cost() {
        return 0;
    }

    private Ref<EntityStore> resolveSourceRef(@Nonnull Ref<EntityStore> selfRef,
                                              @Nonnull Role role,
                                              @Nonnull Store<EntityStore> store) {
        MarkedEntitySupport markedEntitySupport = NpcSupportAccess.markedEntity(role, selfRef, store);
        if (sourceTargetSlot != Integer.MIN_VALUE && markedEntitySupport != null) {
            Ref<EntityStore> sourceRef = markedEntitySupport.getMarkedEntityRef(sourceTargetSlot);
            if (sourceRef != null && sourceRef.isValid()) {
                return sourceRef;
            }
        }
        return useSelfWhenSourceMissing ? selfRef : null;
    }
}
