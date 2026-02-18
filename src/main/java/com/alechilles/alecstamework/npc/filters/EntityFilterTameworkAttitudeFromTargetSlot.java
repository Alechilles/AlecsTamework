package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkAttitudeFromTargetSlot;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.EntityFilterBase;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.EnumSet;
import javax.annotation.Nonnull;

/**
 * Entity filter that checks attitude from a marked target slot toward the candidate target.
 */
public final class EntityFilterTameworkAttitudeFromTargetSlot extends EntityFilterBase {
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
        Ref<EntityStore> sourceRef = resolveSourceRef(ref, role);
        if (sourceRef == null || !sourceRef.isValid()) {
            return false;
        }
        Attitude attitude = role.getWorldSupport().getAttitude(sourceRef, targetRef, store);
        return attitude != null && attitudes.contains(attitude);
    }

    @Override
    public int cost() {
        return 0;
    }

    @Override
    public void registerWithSupport(@Nonnull Role role) {
        role.getWorldSupport().requireAttitudeCache();
    }

    private Ref<EntityStore> resolveSourceRef(@Nonnull Ref<EntityStore> selfRef, @Nonnull Role role) {
        if (sourceTargetSlot != Integer.MIN_VALUE && role.getMarkedEntitySupport() != null) {
            Ref<EntityStore> sourceRef = role.getMarkedEntitySupport().getMarkedEntityRef(sourceTargetSlot);
            if (sourceRef != null && sourceRef.isValid()) {
                return sourceRef;
            }
        }
        return useSelfWhenSourceMissing ? selfRef : null;
    }
}
