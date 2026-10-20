package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
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
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Awards bounded active-time XP exclusively to live bonded companions. A body is one when its
 * companion stamp names a profile the index holds as bonded ({@code bonded}, a pure in-memory
 * lookup that runs every tick).
 */
public final class SummonedCompanionExperienceSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkCompanionComponent> stampType;
    private final Predicate<UUID> bonded;
    private final ComponentType<EntityStore, TameworkLevelingComponent> levelingType;
    private final ComponentType<EntityStore, DeathComponent> deathType;
    private final SummonedCompanionExperienceService experienceService = new SummonedCompanionExperienceService();
    private final ProjectionSettingsResolver settingsResolver;
    private final CompanionXpAwarder xpAwarder;
    private final LongSupplier clock;

    public SummonedCompanionExperienceSystem(
            @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType,
            @Nonnull Predicate<UUID> bonded,
            @Nonnull ComponentType<EntityStore, TameworkLevelingComponent> levelingType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType) {
        this.stampType = stampType;
        this.bonded = bonded;
        this.levelingType = levelingType;
        this.deathType = deathType;
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
            @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType,
            @Nonnull Predicate<UUID> bonded,
            @Nonnull ComponentType<EntityStore, TameworkLevelingComponent> levelingType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathType,
            @Nonnull ProjectionSettingsResolver settingsResolver,
            @Nonnull CompanionXpAwarder xpAwarder,
            @Nonnull LongSupplier clock) {
        this.stampType = stampType;
        this.bonded = bonded;
        this.levelingType = levelingType;
        this.deathType = deathType;
        this.settingsResolver = settingsResolver;
        this.xpAwarder = xpAwarder;
        this.clock = clock;
    }

    @Override
    public Query<EntityStore> getQuery() {
        // NPCEntity's type can be unavailable while runtime participants are preflighted.
        return Query.and(NPCEntity.getComponentType(), stampType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> reference = chunk.getReferenceTo(index);
        TameworkCompanionComponent stamp = chunk.getComponent(index, stampType);
        UUID profileId = stamp == null ? null : stamp.getProfileId();
        // Ordinary companions stop here: only bonded ones carry summoned XP state.
        if (profileId == null || !bonded.test(profileId)) {
            return;
        }
        boolean dead = reference != null && reference.isValid() && store.getComponent(reference, deathType) != null;
        boolean referenceValid = reference != null && reference.isValid();
        if (!referenceValid) {
            return;
        }

        TameworkLevelingComponent leveling = store.getComponent(reference, levelingType);
        ResolvedSettings resolved = !dead ? settingsResolver.resolve(reference, store) : null;
        boolean active = resolved != null;
        if (active && leveling == null) {
            leveling = CompanionLevelingService.ensureLevelingComponent(
                    reference, store, commandBuffer, resolved.roleId());
        }
        if (leveling == null) {
            return;
        }
        long nowMs = clock.getAsLong();
        processProjection(leveling, dead,
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

    /** Advances the summoned XP cadence of a bonded companion; a dead one is paused. */
    private static void processProjection(@Nonnull TameworkLevelingComponent leveling,
                                          boolean dead,
                                          @Nullable TwLevelingConfig.SummonedXpSourceSettings settings,
                                          long nowMs,
                                          double dt,
                                          @Nonnull AwardSink awardSink,
                                          @Nonnull SummonedCompanionExperienceService experienceService) {
        boolean active = !dead && settings != null;
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
