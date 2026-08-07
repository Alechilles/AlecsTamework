package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async bounded reads of canonical snapshot history used by item recovery. */
public final class SqliteCompanionSnapshotReader {
    private static final PersistenceReadKind HISTORY =
            new PersistenceReadKind("companion_snapshot_history");

    private final SqliteReadExecutor reads;

    public SqliteCompanionSnapshotReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Snapshot read executor is required"
            );
        }
        this.reads = reads;
    }

    /** Returns the complete ordered history for one profile and snapshot kind. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<List<CompanionSnapshot>>>
    findHistory(@Nonnull ProfileId profileId, @Nonnull SnapshotKind kind) {
        if (profileId == null || kind == null) {
            throw new IllegalArgumentException(
                    "Snapshot profile and kind are required"
            );
        }
        return reads.execute(new SqliteReadCommand<>(
                HISTORY,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    List<CompanionSnapshot> history =
                            new SqliteCompanionSnapshotStore(connection)
                                    .findHistory(profileId, kind);
                    return PersistenceReadResult.found(
                            history,
                            history.size()
                    );
                }
        ));
    }
}
