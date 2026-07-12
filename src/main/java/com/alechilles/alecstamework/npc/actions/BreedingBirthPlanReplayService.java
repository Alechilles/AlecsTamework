package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.ownership.BreedingBirthPlanSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects a persisted litter on retry and removes only children proven durably committed. */
final class BreedingBirthPlanReplayService {
    private final BreedingBirthPlanSnapshotMapper mapper;

    BreedingBirthPlanReplayService() {
        this(new BreedingBirthPlanSnapshotMapper());
    }

    BreedingBirthPlanReplayService(@Nonnull BreedingBirthPlanSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Nonnull
    Resolution resolve(@Nonnull BreedingPopulationReplayState replay,
                       @Nonnull Supplier<BreedingBirthPlan> newPlan,
                       @Nullable TwBreedingConfig config) {
        if (!replay.usable()) {
            return Resolution.denied(replay.reason());
        }
        BreedingBirthPlan fullPlan;
        BreedingBirthPlanSnapshot snapshot;
        if (replay.birthPlan() == null) {
            fullPlan = newPlan.get();
            if (fullPlan == null) {
                return Resolution.denied("breeding-plan-unavailable");
            }
            snapshot = mapper.snapshot(fullPlan);
        } else {
            snapshot = replay.birthPlan();
            fullPlan = mapper.restore(snapshot, config);
            if (fullPlan == null) {
                return Resolution.denied("breeding-replay-plan-unresolvable");
            }
        }
        Set<String> committed = replay.committedChildKeys();
        List<BreedingBirthPlan.PlannedChild> missing = new ArrayList<>();
        int committedCount = 0;
        for (BreedingBirthPlan.PlannedChild child : fullPlan.children()) {
            if (committed.contains(child.childKey())) {
                committedCount++;
            } else {
                missing.add(child);
            }
        }
        if (committedCount != committed.size()) {
            return Resolution.denied("breeding-replay-child-conflict");
        }
        return new Resolution(
                true,
                "breeding-plan-ready",
                fullPlan,
                new BreedingBirthPlan(fullPlan.fertility(), List.copyOf(missing)),
                snapshot,
                committedCount
        );
    }

    record Resolution(
            boolean allowed,
            @Nonnull String reason,
            @Nullable BreedingBirthPlan fullPlan,
            @Nullable BreedingBirthPlan missingPlan,
            @Nullable BreedingBirthPlanSnapshot snapshot,
            int committedCount
    ) {
        @Nonnull
        static Resolution denied(@Nonnull String reason) {
            return new Resolution(false, reason, null, null, null, 0);
        }
    }
}
