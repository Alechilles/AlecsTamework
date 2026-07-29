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
import java.util.function.LongSupplier;
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
    private final ProjectionSettingsResolver settingsResolver;
    private final CompanionXpAwarder xpAwarder;
    private final LongSupplier clock;

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
        this.settingsResolver = (reference, store) -> {
            String roleId = CompanionRoleIdResolver.resolveRoleId(reference, store);
            TwLevelingConfig config = roleId == null ? null : TwLevelingConfig.resolveForRole(roleId);
            return config != null && config.isEnabled()
                    ? new ResolvedSettings(roleId, config.getXpSources().getSummoned())
                    : null;
        };
        this.xpAwarder = CompanionLevelingService::awardXp;
        this.clock = System::currentTimeMillis;
    }

    SummonedCompanionExperienceSystem(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionIdentityType,
            @Nonnull ComponentType<EntityStore, TameworkLevelingComponent> levelingType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType,
            @Nonnull ProjectionSettingsResolver settingsResolver,
            @Nonnull CompanionXpAwarder xpAwarder,
            @Nonnull LongSupplier clock) {
        this.npcType = npcType;
        this.projectionIdentityType = projectionIdentityType;
        this.levelingType = levelingType;
        this.deathType = deathType;
        this.query = Query.and(npcType, projectionIdentityType);
        this.settingsResolver = settingsResolver;
        this.xpAwarder = xpAwarder;
        this.clock = clock;
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
        boolean referenceValid = reference != null && reference.isValid();
        if (!referenceValid) {
            return;
        }

        TameworkLevelingComponent leveling = store.getComponent(reference, levelingType);
        boolean eligible = isEligibleForSummonedXp(true, identity, dead);
        ResolvedSettings resolved = eligible ? settingsResolver.resolve(reference, store) : null;
        boolean active = resolved != null;
        if (active && leveling == null) {
            leveling = CompanionLevelingService.ensureLevelingComponent(
                    reference, store, commandBuffer, resolved.roleId());
        }
        if (leveling == null) {
            return;
        }
        long nowMs = clock.getAsLong();
        processProjection(leveling, identity, dead,
                active ? resolved.settings() : null,
                nowMs, dt,
                (source, amount) -> xpAwarder.award(
                        reference, store, commandBuffer, resolved.roleId(), source, amount),
                experienceService);
        TameworkLevelingComponent updated = commandBuffer == null ? null
                : commandBuffer.getComponent(reference, levelingType);
        if (updated != null && updated != leveling) {
            applyCadence(updated, new SummonedCompanionExperienceService.State(
                    leveling.getSummonedActiveSeconds(),
                    leveling.getSummonedWindowAwardedXp(),
                    leveling.getSummonedWindowStartedAtMs(),
                    leveling.getSummonedLastSampleAtMs()));
        }
    }

    static boolean isEligibleForSummonedXp(boolean referenceValid,
                                           @Nullable TameworkProjectionIdentityComponent identity,
                                           boolean dead) {
        return referenceValid && !dead && identity != null && identity.isBondedCompanion();
    }

    static void processProjection(@Nonnull TameworkLevelingComponent leveling,
                                  @Nullable TameworkProjectionIdentityComponent identity,
                                  boolean dead,
                                  @Nullable TwLevelingConfig.SummonedXpSourceSettings settings,
                                  long nowMs,
                                  double dt,
                                  @Nonnull AwardSink awardSink) {
        processProjection(leveling, identity, dead, settings, nowMs, dt, awardSink,
                new SummonedCompanionExperienceService());
    }

    private static void processProjection(@Nonnull TameworkLevelingComponent leveling,
                                          @Nullable TameworkProjectionIdentityComponent identity,
                                          boolean dead,
                                          @Nullable TwLevelingConfig.SummonedXpSourceSettings settings,
                                          long nowMs,
                                          double dt,
                                          @Nonnull AwardSink awardSink,
                                          @Nonnull SummonedCompanionExperienceService experienceService) {
        boolean active = isEligibleForSummonedXp(true, identity, dead) && settings != null;
        SummonedCompanionExperienceService.Result result = experienceService.advance(
                new SummonedCompanionExperienceService.State(
                        leveling.getSummonedActiveSeconds(),
                        leveling.getSummonedWindowAwardedXp(),
                        leveling.getSummonedWindowStartedAtMs(),
                        leveling.getSummonedLastSampleAtMs()),
                nowMs, dt, settings, active);
        applyCadence(leveling, result.state());
        if (result.awardedXp() > 0.0d) {
            awardSink.award(CompanionXpSource.SUMMONED, result.awardedXp());
        }
    }

    @FunctionalInterface
    interface AwardSink {
        void award(@Nonnull CompanionXpSource source, double amount);
    }

    @FunctionalInterface
    interface ProjectionSettingsResolver {
        @Nullable ResolvedSettings resolve(@Nonnull Ref<EntityStore> reference,
                                           @Nonnull Store<EntityStore> store);
    }

    @FunctionalInterface
    interface CompanionXpAwarder {
        void award(@Nonnull Ref<EntityStore> reference,
                   @Nonnull Store<EntityStore> store,
                   @Nonnull CommandBuffer<EntityStore> commandBuffer,
                   @Nonnull String roleId,
                   @Nonnull CompanionXpSource source,
                   double amount);
    }

    record ResolvedSettings(@Nonnull String roleId,
                            @Nonnull TwLevelingConfig.SummonedXpSourceSettings settings) {
    }

    private static void applyCadence(@Nonnull TameworkLevelingComponent leveling,
                                     @Nonnull SummonedCompanionExperienceService.State state) {
        leveling.setSummonedActiveSeconds(state.activeSeconds());
        leveling.setSummonedWindowAwardedXp(state.windowAwardedXp());
        leveling.setSummonedWindowStartedAtMs(state.windowStartedAtMs());
        leveling.setSummonedLastSampleAtMs(state.lastSampleAtMs());
    }
}
