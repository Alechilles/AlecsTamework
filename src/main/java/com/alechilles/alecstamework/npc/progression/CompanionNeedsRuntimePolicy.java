package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves `/tw settings` policy for companion needs runtime systems.
 */
final class CompanionNeedsRuntimePolicy {
    private CompanionNeedsRuntimePolicy() {
    }

    static boolean isNeedsEnabled(@Nullable TwNeedsConfig config) {
        return isNeedsEnabled(config, TameworkRuntimeSettings.currentOrNull());
    }

    static boolean isNeedsEnabled(@Nullable TwNeedsConfig config, @Nullable TameworkRuntimeSettings settings) {
        return NeedsConfigResolver.isRuntimeEnabled(config, settings);
    }

    @Nonnull
    static TwNeedsConfig.TickPolicySettings resolveTickPolicy(@Nonnull TwNeedsConfig config) {
        return resolveTickPolicy(config, TameworkRuntimeSettings.currentOrNull());
    }

    @Nonnull
    static TwNeedsConfig.TickPolicySettings resolveTickPolicy(@Nonnull TwNeedsConfig config,
                                                              @Nullable TameworkRuntimeSettings settings) {
        TwNeedsConfig.TickPolicySettings base = config.getTickPolicy();
        return settings == null ? base : settings.resolveNeedsTickPolicy(base);
    }

    @Nonnull
    static TwNeedsConfig.DamageSettings resolveDamage(@Nonnull TwNeedsConfig config) {
        return resolveDamage(config, TameworkRuntimeSettings.currentOrNull());
    }

    @Nonnull
    static TwNeedsConfig.DamageSettings resolveDamage(@Nonnull TwNeedsConfig config,
                                                      @Nullable TameworkRuntimeSettings settings) {
        TwNeedsConfig.DamageSettings base = config.getDamage();
        return settings == null ? base : settings.resolveNeedsDamage(base);
    }
}
