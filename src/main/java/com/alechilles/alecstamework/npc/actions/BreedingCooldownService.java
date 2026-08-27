package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.npc.compat.NpcAlarmAccess;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves configured breeding cooldowns and applies signed-time-safe provisional parent state.
 */
final class BreedingCooldownService {
    static final String BREEDING_COOLDOWN_ALARM_NAME = "Breeding_Cooldown";
    private static final String COOLDOWN_MULTIPLIER_EFFECT_KEY = "BreedCooldownMultiplier";

    @Nonnull
    Resolution resolve(@Nullable TwBreedingConfig config,
                       @Nullable String roleId,
                       @Nullable Ref<EntityStore> npcRef,
                       @Nullable Store<EntityStore> store) {
        TwBreedingConfig.CooldownSettings settings = config == null
                ? null
                : config.resolveCooldowns(roleId);
        int baseSeconds = settings == null ? 600 : Math.max(0, settings.getBaseCooldownSeconds());
        int minDelay = settings == null ? 15 : Math.max(0, settings.getMinDelaySeconds());
        int maxDelay = settings == null ? 45 : Math.max(0, settings.getMaxDelaySeconds());
        if (maxDelay < minDelay) {
            int swap = minDelay;
            minDelay = maxDelay;
            maxDelay = swap;
        }
        int randomDelay = maxDelay > minDelay
                ? ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1)
                : minDelay;
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                COOLDOWN_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double configuredSeconds = ((double) baseSeconds + randomDelay) * multiplier;
        TwBreedingConfig.TimerBasis timerBasis = config == null
                ? TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED
                : config.resolveTiming(roleId).getTimerBasis();
        long durationMs = BreedingTimeService.toGameDurationMs(configuredSeconds, timerBasis, store);
        double currentRate = BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(store);
        double baselineRate = BreedingTimeService.resolveBaselineGameSecondsPerRealSecond(store);
        double realSeconds = durationMs <= 0L || !Double.isFinite(currentRate) || currentRate <= 0.0
                ? 0.0
                : (double) durationMs / (currentRate * 1000.0);
        return new Resolution(
                baseSeconds,
                randomDelay,
                multiplier,
                configuredSeconds,
                timerBasis,
                durationMs,
                currentRate,
                baselineRate,
                realSeconds
        );
    }

    void apply(@Nonnull Ref<EntityStore> npcRef,
               @Nonnull TameworkBreedingComponent breeding,
               @Nullable NPCEntity npc,
               @Nullable UUID partnerUuid,
               @Nonnull Resolution cooldown,
               long nowMs,
               @Nonnull Store<EntityStore> store,
               @Nullable CommandBuffer<EntityStore> commandBuffer) {
        applyParentCooldown(
                npcRef,
                breeding,
                npc,
                partnerUuid,
                cooldown.durationMs(),
                nowMs,
                store,
                commandBuffer
        );
    }

    void applyParentCooldown(@Nonnull Ref<EntityStore> npcRef,
                             @Nonnull TameworkBreedingComponent breeding,
                             @Nullable NPCEntity npc,
                             @Nullable UUID partnerUuid,
                             long cooldownMs,
                             long nowMs,
                             @Nonnull Store<EntityStore> store,
                             @Nullable CommandBuffer<EntityStore> commandBuffer) {
        applyParentCooldown(
                npcRef,
                breeding,
                npc,
                partnerUuid,
                cooldownMs,
                nowMs,
                System.currentTimeMillis(),
                store,
                commandBuffer
        );
    }

    void applyParentCooldown(@Nonnull Ref<EntityStore> npcRef,
                             @Nonnull TameworkBreedingComponent breeding,
                             @Nullable NPCEntity npc,
                             @Nullable UUID partnerUuid,
                             long cooldownMs,
                             long nowMs,
                             long happinessUpdatedAtMs,
                             @Nonnull Store<EntityStore> store,
                             @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (!npcRef.isValid()) {
            return;
        }
        HusbandryOutcomeModifiers outcome = HusbandryOutcomeRuntime.resolve(
                HusbandryOutcomeKind.BREEDING_COOLDOWN,
                npcRef,
                store,
                npc == null ? null : npc.getRoleName(),
                null
        );
        CooldownWindow window = resolveWindow(
                nowMs,
                applyParentOutcomeMultiplier(cooldownMs, outcome.breedingCooldownMultiplier())
        );
        breeding.setReady(false);
        breeding.setCooldownUntilMs(window.untilMs());
        breeding.setCooldownStartedAtMs(window.startedAtMs());
        breeding.setCooldownDurationMs(window.durationMs());
        breeding.setLastPartnerUuid(partnerUuid);
        breeding.setLastHappinessUpdateMs(happinessUpdatedAtMs);
        breeding.clearManualBreedingReady();
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type != null) {
            putComponent(npcRef, store, commandBuffer, type, breeding);
        }
        applyCooldownAlarm(npcRef, npc, window.untilMs(), store);
    }

    @Nonnull
    static CooldownWindow resolveWindow(long nowMs, long cooldownMs) {
        long durationMs = Math.max(0L, cooldownMs);
        return new CooldownWindow(
                BreedingTimeService.deadlineAfter(nowMs, durationMs),
                BreedingTimeService.cooldownStartedAt(nowMs, durationMs),
                durationMs
        );
    }

    /** Applies trait and husbandry multipliers to a parent duration with saturation. */
    static long applyParentOutcomeMultiplier(long baseCooldownMs,
                                             double traitMultiplier,
                                             double outcomeMultiplier) {
        return applyParentOutcomeMultiplier(
                multiplyDuration(baseCooldownMs, traitMultiplier), outcomeMultiplier);
    }

    /** Applies only the husbandry multiplier to an already trait-adjusted duration. */
    static long applyParentOutcomeMultiplier(long cooldownMs, double outcomeMultiplier) {
        return multiplyDuration(cooldownMs, outcomeMultiplier);
    }

    private static long multiplyDuration(long durationMs, double multiplier) {
        if (durationMs <= 0L) {
            return 0L;
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return durationMs;
        }
        double scaled = (double) durationMs * multiplier;
        if (!Double.isFinite(scaled) || scaled >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Math.round(scaled));
    }

    private static void applyCooldownAlarm(Ref<EntityStore> npcRef,
                                           @Nullable NPCEntity npc,
                                           long cooldownUntilMs,
                                           Store<EntityStore> store) {
        if (npc == null || cooldownUntilMs == 0L) {
            return;
        }
        Alarm alarm = NpcAlarmAccess.resolveAlarm(npcRef, store, BREEDING_COOLDOWN_ALARM_NAME);
        if (alarm != null) {
            alarm.set(npcRef, Instant.ofEpochMilli(cooldownUntilMs), store);
        }
    }

    private static <T extends Component<EntityStore>> void putComponent(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer,
            ComponentType<EntityStore, T> componentType,
            T component) {
        if (commandBuffer != null) {
            commandBuffer.putComponent(npcRef, componentType, component);
        } else {
            store.putComponent(npcRef, componentType, component);
        }
    }

    record CooldownWindow(long untilMs, long startedAtMs, long durationMs) {
    }

    record Resolution(int baseSeconds,
                      int randomDelaySeconds,
                      double traitMultiplier,
                      double configuredSeconds,
                      TwBreedingConfig.TimerBasis basis,
                      long durationMs,
                      double currentRate,
                      double baselineRate,
                      double approximateRealSeconds) {
    }
}
