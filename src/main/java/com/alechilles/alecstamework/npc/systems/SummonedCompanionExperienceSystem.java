package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.SummonedCompanionExperienceService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Awards bounded active-time XP exclusively to live bonded companion projections. */
public final class SummonedCompanionExperienceSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionIdentityType;
    private final ComponentType<EntityStore, TameworkLevelingComponent> levelingType;
    private final ComponentType<EntityStore, DeathComponent> deathType;
    private final Query<EntityStore> query;
    private final SummonedCompanionExperienceService experienceService = new SummonedCompanionExperienceService();

    public SummonedCompanionExperienceSystem(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionIdentityType,
            @Nonnull ComponentType<EntityStore, TameworkLevelingComponent> levelingType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType) {
        this.npcType = npcType;
        this.projectionIdentityType = projectionIdentityType;
        this.levelingType = levelingType;
        this.deathType = deathType;
        this.query = Query.and(npcType, projectionIdentityType);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> reference = chunk.getReferenceTo(index);
        TameworkProjectionIdentityComponent identity = chunk.getComponent(index, projectionIdentityType);
        boolean dead = reference != null && reference.isValid() && store.getComponent(reference, deathType) != null;
        if (!isEligibleForSummonedXp(reference != null && reference.isValid(), identity, dead)) {
            return;
        }

        String roleId = CompanionRoleIdResolver.resolveRoleId(reference, store);
        TwLevelingConfig config = roleId == null ? null : TwLevelingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return;
        }

        TameworkLevelingComponent leveling = CompanionLevelingService.ensureLevelingComponent(
                reference, store, commandBuffer, roleId);
        if (leveling == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        SummonedCompanionExperienceService.Result result = experienceService.advance(
                new SummonedCompanionExperienceService.State(
                        leveling.getSummonedActiveSeconds(),
                        leveling.getSummonedWindowAwardedXp(),
                        leveling.getSummonedWindowStartedAtMs(),
                        leveling.getSummonedLastSampleAtMs()
                ),
                nowMs,
                dt,
                config.getXpSources().getSummoned(),
                true
        );

        if (result.awardedXp() > 0.0d) {
            CompanionLevelingService.awardXp(
                    reference, store, commandBuffer, roleId, CompanionXpSource.SUMMONED, result.awardedXp());
        }
        TameworkLevelingComponent updated = commandBuffer.getComponent(reference, levelingType);
        applyCadence(updated == null ? leveling : updated, result.state());
    }

    static boolean isEligibleForSummonedXp(boolean referenceValid,
                                           @Nullable TameworkProjectionIdentityComponent identity,
                                           boolean dead) {
        return referenceValid && !dead && identity != null && identity.isBondedCompanion();
    }

    private static void applyCadence(@Nonnull TameworkLevelingComponent leveling,
                                     @Nonnull SummonedCompanionExperienceService.State state) {
        leveling.setSummonedActiveSeconds(state.activeSeconds());
        leveling.setSummonedWindowAwardedXp(state.windowAwardedXp());
        leveling.setSummonedWindowStartedAtMs(state.windowStartedAtMs());
        leveling.setSummonedLastSampleAtMs(state.lastSampleAtMs());
    }
}
