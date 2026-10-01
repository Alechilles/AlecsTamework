package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.items.CommandLinkedPanelCooldownSnapshotService;
import com.alechilles.alecstamework.items.CommandLoadedNpcStatusSnapshotService;
import com.alechilles.alecstamework.items.CommandNpcNameResolver;
import com.alechilles.alecstamework.npc.movement.MountedNpcSnapshotRoleResolver;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Production summary sources. Each read delegates to the code path the loaded companion panel
 * already uses, so the saved summary matches what the panel shows. World thread only.
 */
public final class HytaleSummarySources implements CompanionSummaries.Sources {
    @Nullable
    @Override
    public String roleId(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        // A mounted or parked body reports Empty_Role; the summary keeps its real role instead.
        String live = CompanionRoleIdResolver.resolveRoleId(ref, store);
        return live == null ? null : MountedNpcSnapshotRoleResolver.durableRoleId(live, ref, store);
    }

    @Nullable
    @Override
    public String nameKey(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null || !ref.isValid()) {
            return null;
        }
        return CommandNpcNameResolver.npcNameKey(store.getComponent(ref, npcType));
    }

    @Nullable
    @Override
    public String iconId(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nullable String roleId) {
        return CommandLoadedNpcStatusSnapshotService.resolvePortrait(ref, store, roleId);
    }

    @Nullable
    @Override
    public float[] health(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        EntityStatValue health = CommandLoadedNpcStatusSnapshotService.readHealthStat(ref, store);
        return health == null ? null : new float[] {health.get(), health.getMax()};
    }

    @Nonnull
    @Override
    public String harvestAlarmName() {
        return CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName();
    }
}
