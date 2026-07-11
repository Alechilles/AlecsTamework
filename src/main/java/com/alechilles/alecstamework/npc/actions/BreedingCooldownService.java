package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves and applies breeding cooldown state for parents and newborn offspring. */
final class BreedingCooldownService {
    private static final String COOLDOWN_ALARM_NAME = "Breeding_Cooldown";
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
               long now,
               @Nonnull Store<EntityStore> store,
               @Nullable CommandBuffer<EntityStore> commandBuffer) {
        long durationMs = Math.max(0L, cooldown.durationMs());
        long until = now + durationMs;
        breeding.setReady(false);
        breeding.setCooldownUntilMs(until);
        breeding.setCooldownStartedAtMs(durationMs > 0L ? now : 0L);
        breeding.setCooldownDurationMs(durationMs);
        breeding.setLastPartnerUuid(partnerUuid);
        breeding.setLastHappinessUpdateMs(now);
        breeding.clearManualBreedingReady();
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type != null) {
            if (commandBuffer == null) {
                store.putComponent(npcRef, type, breeding);
            } else {
                commandBuffer.putComponent(npcRef, type, breeding);
            }
        }
        applyAlarm(npcRef, npc, until, store);
    }

    private static void applyAlarm(@Nonnull Ref<EntityStore> npcRef,
                                   @Nullable NPCEntity npc,
                                   long cooldownUntilMs,
                                   @Nonnull Store<EntityStore> store) {
        if (npc == null || cooldownUntilMs == 0L) {
            return;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return;
        }
        Alarm alarm = alarmStore.get(npc, COOLDOWN_ALARM_NAME);
        if (alarm != null) {
            alarm.set(npcRef, Instant.ofEpochMilli(cooldownUntilMs), store);
        }
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
