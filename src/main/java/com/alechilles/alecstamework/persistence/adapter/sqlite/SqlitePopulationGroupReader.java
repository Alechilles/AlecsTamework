package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async read-lane access to complete group assignment and lag evidence. */
public final class SqlitePopulationGroupReader {
    private static final PersistenceReadKind ASSIGNMENTS =
            new PersistenceReadKind("population_group_assignments");
    private static final PersistenceReadKind STALE =
            new PersistenceReadKind("population_group_stale_profiles");

    private final SqliteReadExecutor reads;

    public SqlitePopulationGroupReader(
            @Nonnull SqliteReadExecutor reads
    ) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Population group read executor is required"
            );
        }
        this.reads = reads;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<
            List<PopulationGroupAssignment>>> findAllAssignments() {
        return reads.execute(new SqliteReadCommand<>(
                ASSIGNMENTS,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    List<PopulationGroupAssignment> assignments =
                            new SqlitePopulationGroupStore(connection)
                                    .findAllAssignments();
                    return PersistenceReadResult.found(
                            assignments, assignments.size()
                    );
                }
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<List<ProfileId>>>
    findStaleProfiles() {
        return reads.execute(new SqliteReadCommand<>(
                STALE,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    List<ProfileId> stale =
                            new SqlitePopulationGroupStore(connection)
                                    .findStaleProfiles();
                    return PersistenceReadResult.found(
                            stale, stale.size()
                    );
                }
        ));
    }
}

