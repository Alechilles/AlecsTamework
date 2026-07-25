package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact canonical evidence for registration, policy refresh, or checkpoint. */
public record TimedSummonLeaseMutationRequest(
        @Nullable TimedSummonLease before,
        @Nonnull TimedSummonLease after,
        @Nonnull CompanionLifecycle lifecycle,
        long requestedAtMs
) {
    public TimedSummonLeaseMutationRequest {
        new TimedSummonLeaseChange(before, after);
        if (lifecycle == null
                || !after.profileId().equals(lifecycle.profileId())
                || lifecycle.activeOperationId() != null
                || lifecycle.quarantined()
                || after.updatedAtMs() != requestedAtMs
                || !compatible(after, lifecycle)
                || before != null && !allowed(before, after)) {
            throw new IllegalArgumentException(
                    "Exact timed summon lease mutation is required"
            );
        }
    }

    private static boolean compatible(
            TimedSummonLease lease,
            CompanionLifecycle lifecycle
    ) {
        return lease.activeSession()
                ? lifecycle.state() == LifecycleState.ACTIVE
                || lifecycle.state() == LifecycleState.UNLOADED
                : lifecycle.state() == LifecycleState.ROSTER_STORED;
    }

    private static boolean allowed(
            TimedSummonLease before,
            TimedSummonLease after
    ) {
        if (before.activeSession() != after.activeSession()
                || !Objects.equals(
                before.cooldownUntilMs(), after.cooldownUntilMs()
        )) {
            return false;
        }
        if (!before.activeSession()) {
            return true;
        }
        return before.sessionId().equals(after.sessionId())
                && before.policy().equals(after.policy())
                && remainingDidNotIncrease(
                before.remainingMs(), after.remainingMs()
        )
                && after.emittedWarningThresholdsMs().containsAll(
                before.emittedWarningThresholdsMs()
        )
                && after.checkpointedAtMs() >= before.checkpointedAtMs();
    }

    private static boolean remainingDidNotIncrease(
            Long before,
            Long after
    ) {
        return before == null
                ? after == null
                : after != null && after <= before;
    }
}

