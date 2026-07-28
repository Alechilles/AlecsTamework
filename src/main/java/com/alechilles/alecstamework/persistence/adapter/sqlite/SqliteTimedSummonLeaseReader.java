package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async read-lane access to canonical timed summon lease detail. */
public final class SqliteTimedSummonLeaseReader {
    private static final PersistenceReadKind LEASES =
            new PersistenceReadKind("timed_summon_leases");

    private final SqliteReadExecutor reads;

    public SqliteTimedSummonLeaseReader(
            @Nonnull SqliteReadExecutor reads
    ) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Timed summon read executor is required"
            );
        }
        this.reads = reads;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<TimedSummonLease>>
    find(@Nonnull ProfileId profileId) {
        if (profileId == null) {
            throw new IllegalArgumentException(
                    "Timed summon profile is required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                LEASES,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteTimedSummonLeaseStore(connection)
                        .find(profileId)
                        .<PersistenceReadResult<TimedSummonLease>>map(
                                lease -> PersistenceReadResult.found(
                                        lease, lease.leaseRevision()
                                )
                        )
                        .orElseGet(PersistenceReadResult::absent)
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<List<TimedSummonLease>>>
    findAll() {
        return reads.execute(new SqliteReadCommand<>(
                LEASES,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    List<TimedSummonLease> leases =
                            new SqliteTimedSummonLeaseStore(connection)
                                    .findAll();
                    long revision = leases.stream()
                            .mapToLong(TimedSummonLease::leaseRevision)
                            .max()
                            .orElse(0);
                    return PersistenceReadResult.found(leases, revision);
                }
        ));
    }
}

