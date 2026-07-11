package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves one parent's provisional cooldown duration without mutating live state. */
final class BreedingParentCooldownResolver {
    private static final String MULTIPLIER_EFFECT_KEY = "BreedCooldownMultiplier";

    @Nonnull
    ResolvedCooldown resolve(@Nullable TwBreedingConfig config,
                             @Nonnull Ref<EntityStore> ref,
                             @Nonnull Store<EntityStore> store) {
        String roleId = roleId(store.getComponent(ref, NPCEntity.getComponentType()));
        TwBreedingConfig.CooldownSettings settings = config != null ? config.resolveCooldowns(roleId) : null;
        int base = settings != null ? Math.max(0, settings.getBaseCooldownSeconds()) : 600;
        int min = settings != null ? Math.max(0, settings.getMinDelaySeconds()) : 15;
        int max = settings != null ? Math.max(0, settings.getMaxDelaySeconds()) : 45;
        if (max < min) {
            int swap = min;
            min = max;
            max = swap;
        }
        int random = max > min ? ThreadLocalRandom.current().nextInt(min, max + 1) : min;
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                ref, store, MULTIPLIER_EFFECT_KEY, 1.0
        );
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double seconds = ((double) base + random) * multiplier;
        TwBreedingConfig.TimerBasis basis = config != null
                ? config.resolveTiming(roleId).getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        long duration = BreedingTimeService.toGameDurationMs(seconds, basis, store);
        return new ResolvedCooldown(base, random, multiplier, seconds, basis, duration);
    }

    @Nullable
    private String roleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null && npc.getRoleIndex() >= 0 ? plugin.getName(npc.getRoleIndex()) : null;
    }

    record ResolvedCooldown(int baseSeconds,
                            int randomDelaySeconds,
                            double traitMultiplier,
                            double configuredSeconds,
                            TwBreedingConfig.TimerBasis basis,
                            long durationMs) {
    }
}
