package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionReviveSettings;
import com.alechilles.alecstamework.config.assets.TwCompanionSummonSettings;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads effective current Tamework assets into immutable feature policy.
 *
 * <p>The revision is a hash of every persisted behavior input. The timed
 * policy uses a nonnegative prefix of that same hash as its numeric revision.</p>
 */
public final class TameworkFeaturePolicySource
        implements ReplacementFeaturePolicySource {
    @Override
    @Nullable
    public RolePolicySnapshot resolve(@Nonnull String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        try {
            String role = roleId.trim();
            TwCompanionConfig scoped = TwCompanionConfig.resolveForRole(role);
            if (scoped == null) {
                scoped = TwCompanionConfig.resolveDefaultConfig();
            }
            TwGlobalConfig global = TwGlobalConfig.resolveActive();
            TwCompanionConfig.EffectiveSettings settings =
                    TwCompanionConfig.EffectiveSettings.from(scoped, global);
            TwCompanionSummonSettings summon = settings.getSummon();
            TwCompanionReviveSettings revive = settings.getRevive();
            List<RevivalCostItem> costs = costs(revive);
            Limits limits = limits(global);
            String configId = scoped == null ? null : scoped.getId();
            String revision = revision(
                    role, configId, limits, summon, revive, costs
            );
            return new RolePolicySnapshot(
                    role,
                    configId,
                    revision,
                    limits.global(),
                    limits.perWorld(),
                    summon.isEnabled(),
                    timedPolicy(configId, revision, summon),
                    revive.isEnabled(),
                    revive.getGameplayCooldownMs(),
                    costs,
                    revive.getInsufficientCostMessage()
            );
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private TimedSummonPolicy timedPolicy(
            String configId,
            String revision,
            TwCompanionSummonSettings settings
    ) {
        long numericRevision = Long.parseLong(
                revision.substring(0, 15), 16
        );
        ArrayList<Long> warnings = new ArrayList<>();
        for (long warning : settings.getExpiryWarningThresholdsMs()) {
            warnings.add(warning);
        }
        return new TimedSummonPolicy(
                configId == null ? "tamework:effective-default" : configId,
                numericRevision,
                settings.getActiveDurationMs(),
                settings.getResummonCooldownMs(),
                settings.isAutoStoreOnOwnerLogout(),
                warnings
        );
    }

    private List<RevivalCostItem> costs(
            TwCompanionReviveSettings settings
    ) {
        ArrayList<RevivalCostItem> costs = new ArrayList<>();
        for (TwItemCostComponent component : settings.getCosts()) {
            costs.add(new RevivalCostItem(
                    component.getItemId(), component.getQuantity()
            ));
        }
        return List.copyOf(costs);
    }

    private Limits limits(TwGlobalConfig global) {
        if (global == null) {
            return new Limits(0, 0);
        }
        int limit = global.getPopulationLimitPerPlayerOwnedTotal();
        return global.getPopulationPerPlayerLimitScope()
                == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                ? new Limits(limit, 0)
                : new Limits(0, limit);
    }

    private String revision(
            String role,
            String configId,
            Limits limits,
            TwCompanionSummonSettings summon,
            TwCompanionReviveSettings revive,
            List<RevivalCostItem> costs
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, role);
        append(canonical, configId == null ? "" : configId);
        append(canonical, Integer.toString(limits.global()));
        append(canonical, Integer.toString(limits.perWorld()));
        append(canonical, Boolean.toString(summon.isEnabled()));
        append(canonical, Long.toString(summon.getActiveDurationMs()));
        append(canonical, Long.toString(summon.getResummonCooldownMs()));
        append(canonical, Boolean.toString(
                summon.isAutoStoreOnOwnerLogout()
        ));
        for (long threshold : summon.getExpiryWarningThresholdsMs()) {
            append(canonical, Long.toString(threshold));
        }
        append(canonical, Boolean.toString(revive.isEnabled()));
        append(canonical, Long.toString(revive.getGameplayCooldownMs()));
        for (RevivalCostItem cost : costs) {
            append(canonical, cost.itemId());
            append(canonical, Integer.toString(cost.quantity()));
        }
        append(
                canonical,
                revive.getInsufficientCostMessage() == null
                        ? ""
                        : revive.getInsufficientCostMessage()
        );
        return Sha256Hash.ofUtf8(canonical.toString()).toString();
    }

    private void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private record Limits(int global, int perWorld) {
    }
}
