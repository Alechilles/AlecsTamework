package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTime;
import com.alechilles.alecstamework.items.persistence
        .TameworkDormantSnapshotFactsReader;
import javax.annotation.Nullable;

/** Resolves the exact current death-or-loss source admitted by paid revival. */
final class PaidRevivalDormantSource {
    private PaidRevivalDormantSource() {
    }

    static boolean supports(LifecycleState state) {
        return expectedKind(state) != null;
    }

    @Nullable
    static CompanionSnapshot exact(CompanionProfileReadModel profile) {
        SnapshotKind expected = expectedKind(profile.lifecycle().state());
        if (expected == null) {
            return null;
        }
        CompanionSnapshot result = null;
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            if (!snapshot.current()
                    || !snapshot.profileId().equals(
                    profile.identity().profileId()
            )
                    || !snapshot.kind().equals(expected)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = snapshot;
        }
        return result;
    }

    @Nullable
    static Long availableAt(
            TameworkDormantSnapshotFactsReader.Facts facts,
            long deathCooldownMs
    ) {
        if (facts.state() == LifecycleState.LOST) {
            return null;
        }
        if (facts.state() != LifecycleState.DEAD_REVIVABLE) {
            throw new IllegalArgumentException(
                    "Paid revival requires death or lost facts"
            );
        }
        return TimedSummonTime.saturatingAdd(
                facts.observedAtMs(), deathCooldownMs
        );
    }

    @Nullable
    private static SnapshotKind expectedKind(LifecycleState state) {
        if (state == LifecycleState.DEAD_REVIVABLE) {
            return DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind();
        }
        return state == LifecycleState.LOST
                ? DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL.snapshotKind()
                : null;
    }
}
