package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import java.util.List;
import java.util.Objects;

/** Replays bounded exact cleanup records without interpreting lease lifecycle. */
final class SqliteBondedCompanionCleanupReplay {
    private final SqliteBondedCompanionCleanupQueue queue;

    SqliteBondedCompanionCleanupReplay(
            SqliteConnectionFactory connections
    ) {
        queue = new SqliteBondedCompanionCleanupQueue(connections);
    }

    int pendingForWorld(
            BondedCompanionProjectionCleanupService cleanup,
            String worldKey,
            long nowMs,
            int limit
    ) {
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(worldKey, "worldKey");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        List<BondedCompanionProjectionCleanupService.CleanupIntent> pending =
                queue.pendingForWorld(worldKey, nowMs, limit);
        int attempted = 0;
        for (var intent : pending) {
            BondedCompanionProjectionCleanupService.Outcome outcome =
                    BondedCompanionRecord.Cleanup.LEGACY_UNKNOWN_WORLD.equals(
                            intent.worldKey())
                            ? BondedCompanionProjectionCleanupService.Outcome
                            .IDENTITY_MISMATCH
                            : cleanup.recover(intent);
            queue.recordOutcome(
                    intent, retryUnconfirmedProjectionAbsence(intent, outcome),
                    nowMs);
            attempted++;
        }
        return attempted;
    }

    BondedCompanionProjectionCleanupService.Outcome attempt(
            BondedCompanionProjectionCleanupService cleanup,
            BondedCompanionProjectionCleanupService.CleanupIntent intent,
            long nowMs
    ) {
        BondedCompanionProjectionCleanupService.Outcome outcome =
                retryUnconfirmedProjectionAbsence(
                        intent, cleanup.recover(intent));
        queue.recordOutcome(intent, outcome, nowMs);
        return outcome;
    }

    private BondedCompanionProjectionCleanupService.Outcome
    retryUnconfirmedProjectionAbsence(
            BondedCompanionProjectionCleanupService.CleanupIntent intent,
            BondedCompanionProjectionCleanupService.Outcome outcome
    ) {
        if (intent.target()
                == BondedCompanionProjectionCleanupService.Target.PROJECTION
                && outcome == BondedCompanionProjectionCleanupService.Outcome
                .ALREADY_MISSING) {
            return BondedCompanionProjectionCleanupService.Outcome.RETRY_REQUIRED;
        }
        return outcome;
    }
}
