package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.damage.DamageTargetMemoryService;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkAttackedTargetSlotRecently;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.EntityFilterBase;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/**
 * Entity filter that matches when the candidate has recently damaged the entity in a target slot.
 */
public final class EntityFilterTameworkAttackedTargetSlotRecently extends EntityFilterBase {
    public static final String TYPE = "TameworkAttackedTargetSlotRecently";

    private final int sourceTargetSlot;
    private final double maxAgeSeconds;
    private final boolean useSelfWhenSourceMissing;
    private final boolean includeSelfAsSource;
    private final DamageTargetMemoryService damageMemoryService = DamageTargetMemoryService.getInstance();

    public EntityFilterTameworkAttackedTargetSlotRecently(
            @Nonnull BuilderEntityFilterTameworkAttackedTargetSlotRecently builder,
            @Nonnull BuilderSupport support) {
        this.sourceTargetSlot = builder.getSourceTargetSlot(support);
        this.maxAgeSeconds = builder.getMaxAgeSeconds(support);
        this.useSelfWhenSourceMissing = builder.useSelfWhenSourceMissing(support);
        this.includeSelfAsSource = builder.includeSelfAsSource(support);
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
        long maxAgeMs = maxAgeSeconds < 0.0 ? -1L : Math.round(maxAgeSeconds * 1000.0);
        long nowMs = resolveCurrentTimeMs(store);
        if (sourceRef != null && sourceRef.isValid()
                && damageMemoryService.hasRecentHit(targetRef, sourceRef, maxAgeMs, nowMs)) {
            return true;
        }
        return includeSelfAsSource && damageMemoryService.hasRecentHit(targetRef, ref, maxAgeMs, nowMs);
    }

    @Override
    public int cost() {
        return 0;
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

    private long resolveCurrentTimeMs(@Nonnull Store<EntityStore> store) {
        WorldTimeResource worldTime = store.getResource(WorldTimeResource.getResourceType());
        return worldTime != null ? worldTime.getGameTime().toEpochMilli() : System.currentTimeMillis();
    }
}
