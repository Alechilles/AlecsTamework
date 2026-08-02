package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies purchased talent effects to finite bonded-companion summon timers. */
final class BondedCompanionTalentTimerPolicyModifier {
    static final String SESSION_DURATION_MULTIPLIER =
            "SummonSessionDurationMultiplier";
    static final String COOLDOWN_MULTIPLIER = "SummonCooldownMultiplier";

    private BondedCompanionTalentTimerPolicyModifier() {
    }

    @Nonnull
    static BondedCompanionPolicy apply(@Nonnull BondedCompanionPolicy policy,
                                       @Nonnull BondedCompanionProfile profile) {
        TameworkTalentsComponent talents = profile.snapshot()
                .fullState().talents();
        TwTalentConfig config = TwTalentConfig.resolveForRole(profile.roleId());
        return apply(policy, talents, config);
    }

    @Nonnull
    static BondedCompanionPolicy apply(@Nonnull BondedCompanionPolicy policy,
                                       @Nullable TameworkTalentsComponent talents,
                                       @Nullable TwTalentConfig config) {
        if (talents == null || config == null || !config.isEnabled()) {
            return policy;
        }
        String[] purchasedTalentIds = talents.getPurchasedTalentIds();
        long sessionDurationSeconds = scaleFiniteTimer(
                policy.sessionDurationSeconds(),
                CompanionTalentService.resolvePurchasedEffectMultiplier(
                        config, purchasedTalentIds,
                        SESSION_DURATION_MULTIPLIER, 1.0D
                )
        );
        long summonCooldownSeconds = scaleFiniteTimer(
                policy.summonCooldownSeconds(),
                CompanionTalentService.resolvePurchasedEffectMultiplier(
                        config, purchasedTalentIds,
                        COOLDOWN_MULTIPLIER, 1.0D
                )
        );
        if (sessionDurationSeconds == policy.sessionDurationSeconds()
                && summonCooldownSeconds == policy.summonCooldownSeconds()) {
            return policy;
        }
        return new BondedCompanionPolicy(
                policy.revision(), policy.rosterId(), policy.familyId(),
                policy.allowedRoles(), policy.maximumOwned(),
                policy.maximumActive(), sessionDurationSeconds,
                summonCooldownSeconds, policy.summonAuraEffectId(),
                policy.revivePrice(), policy.features()
        );
    }

    private static long scaleFiniteTimer(long baseSeconds, double multiplier) {
        if (baseSeconds == 0L || !Double.isFinite(multiplier)
                || multiplier <= 0.0D || multiplier == 1.0D) {
            return baseSeconds;
        }
        double scaled = baseSeconds * multiplier;
        if (!Double.isFinite(scaled) || scaled >= Long.MAX_VALUE) {
            return baseSeconds;
        }
        long rounded = Math.round(scaled);
        return rounded > 0L ? rounded : baseSeconds;
    }
}
