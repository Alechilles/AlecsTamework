package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The single population-cap authority (spec 8.10): checks a record change against the owner's
 * records in the companion index and the current config. Callers hold the index lock, so the
 * count and the change they guard are one step. The rules are resolved on each call from
 * in-memory config, so a reload applies to the next change.
 */
public final class CompanionAdmissionGate {
    private final CompanionIndex index;
    private final Supplier<PopulationGroupConfigIndex> groups;

    /** {@code groups} returns the current population-group config; null is read as no groups. */
    public CompanionAdmissionGate(@Nonnull CompanionIndex index, @Nonnull Supplier<PopulationGroupConfigIndex> groups) {
        this.index = Objects.requireNonNull(index, "index");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    /**
     * Returns why {@code after} may not replace {@code before} (null for a new record), or null
     * when it is admitted. Call it under the index lock, in the step that applies the change.
     */
    @Nullable
    public CompanionAdmission.Refusal refuse(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        if (after.ownerUuid() == null) {
            return null;
        }
        return CompanionAdmission.check(index.fileRecords(after.ownerUuid()), before, after, rules());
    }

    @Nonnull
    private CompanionAdmission.Rules rules() {
        TwGlobalConfig active = TwGlobalConfig.resolveActive();
        TwGlobalConfig global = active == null ? TwGlobalConfig.defaultConfig() : active;
        int ownedLimit = TameworkRuntimeSettings.populationLimitPerPlayerOwnedTotal(
                global.getPopulationLimitPerPlayerOwnedTotal());
        boolean perWorld = TameworkRuntimeSettings.populationPerPlayerLimitScope(
                global.getPopulationPerPlayerLimitScope()) == TwGlobalConfig.PerPlayerLimitScope.PER_WORLD;
        PopulationGroupConfigIndex current = groups.get();
        PopulationGroupConfigIndex groupIndex = current == null ? PopulationGroupConfigIndex.empty() : current;
        return new CompanionAdmission.Rules(Math.max(0, ownedLimit), perWorld, groupIndex::resolvePoliciesForRole);
    }
}
